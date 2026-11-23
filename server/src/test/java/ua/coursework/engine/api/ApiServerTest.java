package ua.coursework.engine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.testtools.JavalinTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import ua.coursework.engine.index.IndexManager;
import ua.coursework.engine.index.IndexSnapshot;
import ua.coursework.engine.index.IndexingInProgressException;
import ua.coursework.engine.index.Tokenizer;
import ua.coursework.engine.llm.OllamaClient;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ApiServerTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path dataDir;

  /** Port 1 is closed, so Ollama is "unavailable" and the tests need no network. */
  private ApiServer apiServer(IndexManager manager, Path dir) {
    return new ApiServer(new OllamaClient("http://localhost:1"), manager, dir);
  }

  private ApiServer apiServer() {
    return apiServer(new IndexManager(new Tokenizer()), dataDir);
  }

  private void writeBooks(String... names) throws IOException {
    for (String name : names) {
      Files.writeString(dataDir.resolve(name), "words about " + name.replace('.', ' '));
    }
  }

  private static JsonNode json(HttpResponse<String> response) throws IOException {
    return JSON.readTree(response.body());
  }

  private static Set<String> fieldNames(JsonNode node) {
    Set<String> names = new HashSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static HttpResponse<String> get(int port, String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  /** POST with a raw body (null means no body at all). */
  private static HttpResponse<String> post(int port, String path, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void healthReturns200WithStatusOk() {
    JavalinTest.test(
        apiServer().create(),
        (server, client) -> {
          var response = client.get("/api/health");
          assertEquals(200, response.code());

          JsonNode json = JSON.readTree(response.body().string());
          assertEquals("ok", json.get("status").asText());
          assertTrue(json.has("ollama"));
          assertEquals(false, json.get("ollama").asBoolean());
        });
  }

  @Test
  void statsBeforeAnyIndexingAreZero() {
    JavalinTest.test(
        apiServer().create(),
        (server, client) -> {
          HttpResponse<String> response = get(server.port(), "/api/stats");

          assertEquals(200, response.statusCode());
          JsonNode json = json(response);
          assertEquals(Set.of("documents", "index_build_time_ms", "threads_used"), fieldNames(json));
          assertEquals(0, json.get("documents").asInt());
          assertEquals(0, json.get("index_build_time_ms").asLong());
          assertEquals(0, json.get("threads_used").asInt());
        });
  }

  @Test
  void indexThenStatsFollowTheContract() throws IOException {
    writeBooks("one.txt", "two.txt", "three.txt");

    JavalinTest.test(
        apiServer().create(),
        (server, client) -> {
          HttpResponse<String> indexResponse =
              post(server.port(), "/api/index", "{\"threads\": 2}");

          assertEquals(200, indexResponse.statusCode());
          JsonNode indexJson = json(indexResponse);
          assertEquals(Set.of("status", "documents_indexed", "time_ms"), fieldNames(indexJson));
          assertEquals("ok", indexJson.get("status").asText());
          assertEquals(3, indexJson.get("documents_indexed").asInt());
          assertTrue(indexJson.get("time_ms").asLong() >= 0);

          JsonNode stats = json(get(server.port(), "/api/stats"));
          assertEquals(3, stats.get("documents").asInt());
          assertEquals(2, stats.get("threads_used").asInt());
          assertEquals(
              indexJson.get("time_ms").asLong(), stats.get("index_build_time_ms").asLong());
        });
  }

  @Test
  void indexingAgainReplacesTheIndex() throws IOException {
    writeBooks("one.txt", "two.txt", "three.txt");

    JavalinTest.test(
        apiServer().create(),
        (server, client) -> {
          assertEquals(
              3, json(post(server.port(), "/api/index", "{\"threads\": 4}")).get("documents_indexed").asInt());

          Files.delete(dataDir.resolve("three.txt"));
          JsonNode second = json(post(server.port(), "/api/index", "{\"threads\": 1}"));

          assertEquals(2, second.get("documents_indexed").asInt());
          JsonNode stats = json(get(server.port(), "/api/stats"));
          assertEquals(2, stats.get("documents").asInt());
          assertEquals(1, stats.get("threads_used").asInt());
        });
  }

  @Test
  void emptyBodyUsesTheDefaultNumberOfThreads() throws IOException {
    writeBooks("one.txt");

    JavalinTest.test(
        apiServer().create(),
        (server, client) -> {
          HttpResponse<String> response = post(server.port(), "/api/index", null);

          assertEquals(200, response.statusCode());
          assertEquals(1, json(response).get("documents_indexed").asInt());
          assertTrue(json(get(server.port(), "/api/stats")).get("threads_used").asInt() >= 1);
        });
  }

  @Test
  void invalidRequestBodyGives400AndLeavesTheIndexAlone() throws IOException {
    writeBooks("one.txt");

    JavalinTest.test(
        apiServer().create(),
        (server, client) -> {
          for (String body : new String[] {"{\"threads\": 0}", "{\"threads\": -2}",
              "{\"threads\": \"many\"}", "{\"threads\": 100000}", "not json"}) {
            HttpResponse<String> response = post(server.port(), "/api/index", body);
            assertEquals(400, response.statusCode(), body);
            assertEquals("error", json(response).get("status").asText(), body);
          }
          assertEquals(0, json(get(server.port(), "/api/stats")).get("documents").asInt());
        });
  }

  @Test
  void indexingWhileAnotherIsRunningGives409() {
    IndexManager busy =
        new IndexManager(new Tokenizer()) {
          @Override
          public IndexSnapshot rebuild(Path dir, int threads) {
            throw new IndexingInProgressException();
          }
        };

    JavalinTest.test(
        apiServer(busy, dataDir).create(),
        (server, client) -> {
          HttpResponse<String> response = post(server.port(), "/api/index", "{\"threads\": 2}");

          assertEquals(409, response.statusCode());
          assertEquals("error", json(response).get("status").asText());
        });
  }

  @Test
  void missingDataDirectoryGives500() {
    JavalinTest.test(
        apiServer(new IndexManager(new Tokenizer()), dataDir.resolve("does-not-exist")).create(),
        (server, client) -> {
          HttpResponse<String> response = post(server.port(), "/api/index", "{\"threads\": 2}");

          assertEquals(500, response.statusCode());
          assertEquals("error", json(response).get("status").asText());
        });
  }
}
