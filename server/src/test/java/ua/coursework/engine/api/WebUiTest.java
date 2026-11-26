package ua.coursework.engine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import ua.coursework.engine.concurrent.ThreadPool;
import ua.coursework.engine.index.IndexManager;
import ua.coursework.engine.index.Tokenizer;
import ua.coursework.engine.llm.OllamaClient;
import ua.coursework.engine.search.SearchService;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WebUiTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path dataDir;

  private Javalin newApp(ThreadPool pool) {
    Tokenizer tokenizer = new Tokenizer();
    IndexManager manager = new IndexManager(tokenizer);
    return new ApiServer(
            new OllamaClient("http://localhost:1"),
            manager,
            new SearchService(manager, tokenizer, dataDir),
            dataDir,
            pool)
        .create();
  }

  private static HttpResponse<String> get(int port, String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String resource(String name) throws IOException {
    try (InputStream in = WebUiTest.class.getResourceAsStream("/web/" + name)) {
      assertTrue(in != null, "resource /web/" + name + " is on the classpath");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void rootServesTheHtmlPageWithTheSearchForm() throws Exception {
    ThreadPool pool = new ThreadPool(1);
    try {
      JavalinTest.test(
          newApp(pool),
          (server, client) -> {
            HttpResponse<String> response = get(server.port(), "/");

            assertEquals(200, response.statusCode());
            assertTrue(
                response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
            String html = response.body();
            assertTrue(html.contains("<form id=\"search-form\""), "search form");
            assertTrue(html.contains("<input id=\"q\""), "query field");
            assertTrue(html.contains("type=\"submit\""), "submit button");
            assertTrue(html.contains("lang=\"uk\""), "Ukrainian page");
            assertTrue(html.contains("Розширення запиту (Ollama)"));
            assertTrue(html.contains("Пошук за ключовими словами"));
            assertTrue(html.contains("Готово"));
          });
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void indexHtmlAndTheScriptAndTheStylesAreServed() throws Exception {
    ThreadPool pool = new ThreadPool(1);
    try {
      JavalinTest.test(
          newApp(pool),
          (server, client) -> {
            assertEquals(200, get(server.port(), "/index.html").statusCode());

            HttpResponse<String> script = get(server.port(), "/app.js");
            assertEquals(200, script.statusCode());
            assertTrue(script.headers().firstValue("Content-Type").orElse("").contains("javascript"));
            assertEquals(resource("app.js"), script.body());

            HttpResponse<String> styles = get(server.port(), "/style.css");
            assertEquals(200, styles.statusCode());
            assertTrue(styles.headers().firstValue("Content-Type").orElse("").startsWith("text/css"));
            assertTrue(styles.body().contains("prefers-color-scheme: dark"));
          });
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void aMissingFileIsStillNotFound() throws Exception {
    ThreadPool pool = new ThreadPool(1);
    try {
      JavalinTest.test(
          newApp(pool),
          (server, client) -> {
            assertEquals(404, get(server.port(), "/no-such-file.js").statusCode());
            assertEquals(404, get(server.port(), "/api/unknown").statusCode());
          });
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void staticFilesDoNotHideTheApi() throws Exception {
    ThreadPool pool = new ThreadPool(1);
    try {
      JavalinTest.test(
          newApp(pool),
          (server, client) -> {
            HttpResponse<String> health = get(server.port(), "/api/health");
            assertEquals(200, health.statusCode());
            assertTrue(health.headers().firstValue("Content-Type").orElse("").contains("json"));
            JsonNode body = JSON.readTree(health.body());
            assertEquals("ok", body.get("status").asText());
            assertFalse(body.get("ollama").asBoolean());

            HttpResponse<String> stats = get(server.port(), "/api/stats");
            assertEquals(200, stats.statusCode());
            assertEquals(0, JSON.readTree(stats.body()).get("documents").asInt());
          });
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void staticFilesDoNotHideTheWebSocket() throws Exception {
    Files.writeString(dataDir.resolve("moby.txt"), "the white whale and the harpoon");
    ThreadPool pool = new ThreadPool(2);
    try {
      JavalinTest.test(
          newApp(pool),
          (server, client) -> {
            try (WsTestClient ws = WsTestClient.connect(server.port())) {
              ws.send("{\"query\": \"whale\"}");
              List<String> messages = ws.readUntilDone(20_000);

              assertEquals(3, messages.size(), messages.toString());
              assertTrue(messages.get(0).contains("\"stage\":\"query_expansion\""));
              assertTrue(messages.get(1).contains("\"stage\":\"keyword_search\""));
              assertTrue(messages.get(2).contains("\"stage\":\"done\""));
            }
            assertEquals(200, get(server.port(), "/").statusCode());
          });
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void aServerWithoutTheWebSocketStillServesThePageAndTheApi() throws Exception {
    Tokenizer tokenizer = new Tokenizer();
    IndexManager manager = new IndexManager(tokenizer);
    Javalin app =
        new ApiServer(
                new OllamaClient("http://localhost:1"),
                manager,
                new SearchService(manager, tokenizer, dataDir),
                dataDir)
            .create();
    JavalinTest.test(
        app,
        (server, client) -> {
          assertEquals(200, get(server.port(), "/").statusCode());
          assertEquals(200, get(server.port(), "/api/health").statusCode());
        });
  }

  @Test
  void thePageLoadsNothingFromOtherSites() throws Exception {
    String html = resource("index.html");
    String script = resource("app.js");
    String styles = resource("style.css");

    for (String raw : List.of(html, script, styles)) {
      // svg namespace is an identifier, not a request
      String text = raw.replace("http://www.w3.org/2000/svg", "");
      assertFalse(text.contains("http://"), "no absolute http address");
      assertFalse(text.contains("https://"), "no absolute https address");
      assertFalse(text.contains("//cdn"), "no CDN");
      assertFalse(text.contains("@import"), "no CSS import");
    }
    assertTrue(html.contains("Content-Security-Policy"));
    assertTrue(html.contains("default-src 'self'"));
    assertFalse(html.contains("<script>"), "no inline script");
    assertFalse(html.contains("<style"), "no inline style block");
    assertFalse(html.contains(" style=\""), "no inline style attribute");
  }

  @Test
  void theScriptNeverBuildsHtmlFromServerData() throws Exception {
    String script = resource("app.js");

    for (String forbidden :
        List.of(
            "innerHTML",
            "outerHTML",
            "insertAdjacentHTML",
            "document.write",
            "eval(",
            "new Function",
            "DOMParser",
            "createContextualFragment")) {
      assertFalse(script.contains(forbidden), "app.js must not use " + forbidden);
    }
    assertTrue(script.contains("textContent"), "text is inserted as text");
  }

  @Test
  void thePageHasBothColourThemesAndAccessibleStatuses() throws Exception {
    String html = resource("index.html");
    String styles = resource("style.css");

    assertTrue(styles.contains("@media (prefers-color-scheme: dark)"));
    assertTrue(styles.contains("prefers-reduced-motion"));
    assertTrue(styles.contains(":focus-visible"));
    assertTrue(html.contains("name=\"viewport\""));
    assertTrue(html.contains("aria-live=\"polite\""));
    assertTrue(html.contains("role=\"alert\""));
    assertTrue(html.contains("<label for=\"q\""));
  }
}
