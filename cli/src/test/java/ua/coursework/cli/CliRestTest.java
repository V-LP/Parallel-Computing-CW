package ua.coursework.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ua.coursework.cli.CliTestSupport.deadUrl;
import static ua.coursework.cli.CliTestSupport.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ua.coursework.cli.CliTestSupport.Result;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CliRestTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String RESULTS =
      "{\"results\":[{\"doc_id\":\"a/moby.txt#3\",\"score\":1.0,\"snippet\":\"Call me Ishmael.\"},"
          + "{\"doc_id\":\"b.txt\",\"score\":0.4321,\"snippet\":\"second\"}],\"time_ms\":12}";

  @Test
  void restSearchPrintsNumberedResultsTimeAndDone() throws Exception {
    try (FakeHttpServer server = new FakeHttpServer().on("/api/search", 200, RESULTS).start()) {
      Result result = run("--rest", "--url", server.url(), "white whale");

      assertEquals(0, result.code(), result.err());
      assertEquals(
          "1. a/moby.txt#3  1.0000\n"
              + "    Call me Ishmael.\n"
              + "2. b.txt  0.4321\n"
              + "    second\n"
              + "Час пошуку: 12 мс\n"
              + "Готово.\n",
          result.out());
      assertEquals("", result.err());

      JsonNode body = JSON.readTree(server.lastBody("/api/search"));
      assertEquals("white whale", body.get("query").asText());
      assertEquals("keyword", body.get("mode").asText());
    }
  }

  @Test
  void restSearchWithJsonPrintsTheRawResponse() throws Exception {
    try (FakeHttpServer server = new FakeHttpServer().on("/api/search", 200, RESULTS).start()) {
      Result result = run("--rest", "--json", "--url", server.url(), "q");
      assertEquals(0, result.code());
      assertEquals(RESULTS + "\n", result.out());
    }
  }

  @Test
  void restSearchWithoutResultsSaysSo() throws Exception {
    try (FakeHttpServer server =
        new FakeHttpServer().on("/api/search", 200, "{\"results\":[],\"time_ms\":1}").start()) {
      Result result = run("--rest", "--url", server.url(), "zzz");
      assertEquals(0, result.code());
      assertEquals("Нічого не знайдено.\nЧас пошуку: 1 мс\nГотово.\n", result.out());
    }
  }

  @Test
  void cyrillicQueryTravelsAsUtf8AndCyrillicAnswerIsPrinted() throws Exception {
    String answer =
        "{\"results\":[{\"doc_id\":\"книга.txt#1\",\"score\":1.0,\"snippet\":\"біла вага\"}],"
            + "\"time_ms\":3}";
    try (FakeHttpServer server = new FakeHttpServer().on("/api/search", 200, answer).start()) {
      Result result = run("--rest", "--url", server.url(), "біла вага");

      assertEquals(0, result.code(), result.err());
      assertEquals("біла вага", JSON.readTree(server.lastBody("/api/search")).get("query").asText());
      assertTrue(result.out().contains("1. книга.txt#1  1.0000\n    біла вага\n"), result.out());
    }
  }

  @Test
  void aLongSnippetIsWrappedInTheOutput() throws Exception {
    String snippet = ("слово ".repeat(50)).trim();
    String answer =
        "{\"results\":[{\"doc_id\":\"d\",\"score\":1.0,\"snippet\":\"" + snippet + "\"}],\"time_ms\":1}";
    try (FakeHttpServer server = new FakeHttpServer().on("/api/search", 200, answer).start()) {
      Result result = run("--rest", "--url", server.url(), "q");
      for (String line : result.out().split("\n")) {
        assertTrue(line.length() <= ResultFormatter.LINE_WIDTH, "too wide: " + line);
      }
    }
  }

  @Test
  void queryCanBeReadFromStdin() throws Exception {
    try (FakeHttpServer server = new FakeHttpServer().on("/api/search", 200, RESULTS).start()) {
      Result result = run("\uFEFFбіла\nвага\n", Map.of(), "--rest", "--url", server.url(), "-");

      assertEquals(0, result.code(), result.err());
      assertEquals("біла вага", JSON.readTree(server.lastBody("/api/search")).get("query").asText());
    }
  }

  @Test
  void emptyStdinQueryIsAUsageError() {
    Result result = run("  \n", Map.of(), "--url", "http://127.0.0.1:1", "-");
    assertEquals(2, result.code());
    assertTrue(result.err().contains("Порожній запит"));
  }

  @Test
  void healthIsPrintedReadably() throws Exception {
    try (FakeHttpServer server =
        new FakeHttpServer().on("/api/health", 200, "{\"status\":\"ok\",\"ollama\":true}").start()) {
      Result result = run("--health", "--url", server.url());
      assertEquals(0, result.code(), result.err());
      assertEquals("Сервер: ok\nOllama: доступна\n", result.out());
    }
  }

  @Test
  void healthWithoutOllamaSaysThatSearchStillWorks() throws Exception {
    try (FakeHttpServer server =
        new FakeHttpServer().on("/api/health", 200, "{\"status\":\"ok\",\"ollama\":false}").start()) {
      Result result = run("--health", "--url", server.url());
      assertEquals(0, result.code());
      assertTrue(result.out().contains("Ollama: недоступна"), result.out());
    }
  }

  @Test
  void healthWithJsonPrintsTheRawResponse() throws Exception {
    String body = "{\"status\":\"ok\",\"ollama\":false}";
    try (FakeHttpServer server = new FakeHttpServer().on("/api/health", 200, body).start()) {
      Result result = run("--health", "--json", "--url", server.url());
      assertEquals(body + "\n", result.out());
    }
  }

  @Test
  void statsArePrintedReadably() throws Exception {
    String body = "{\"documents\":15234,\"index_build_time_ms\":48210,\"threads_used\":8}";
    try (FakeHttpServer server = new FakeHttpServer().on("/api/stats", 200, body).start()) {
      Result result = run("--stats", "--url", server.url());
      assertEquals(0, result.code(), result.err());
      assertEquals(
          "Документів: 15234\nЧас побудови індексу: 48210 мс\nПотоків індексації: 8\n", result.out());
    }
  }

  @Test
  void statsOfAnEmptyIndexSuggestIndexing() throws Exception {
    String body = "{\"documents\":0,\"index_build_time_ms\":0,\"threads_used\":0}";
    try (FakeHttpServer server = new FakeHttpServer().on("/api/stats", 200, body).start()) {
      Result result = run("--stats", "--url", server.url());
      assertTrue(result.out().contains("search --index"), result.out());
    }
  }

  @Test
  void indexWithoutThreadsSendsAnEmptyObject() throws Exception {
    String body = "{\"status\":\"ok\",\"documents_indexed\":15234,\"time_ms\":5000}";
    try (FakeHttpServer server = new FakeHttpServer().on("/api/index", 200, body).start()) {
      Result result = run("--index", "--url", server.url());

      assertEquals(0, result.code(), result.err());
      assertEquals("Проіндексовано документів: 15234\nЧас: 5000 мс (5.0 с)\n", result.out());
      assertEquals(0, JSON.readTree(server.lastBody("/api/index")).size());
      assertTrue(result.err().contains("Індексація запущена"), "progress goes to stderr");
    }
  }

  @Test
  void indexSendsTheNumberOfThreads() throws Exception {
    String body = "{\"status\":\"ok\",\"documents_indexed\":10,\"time_ms\":100}";
    try (FakeHttpServer server = new FakeHttpServer().on("/api/index", 200, body).start()) {
      Result result = run("--index", "4", "--url", server.url());

      assertEquals(0, result.code(), result.err());
      assertEquals(4, JSON.readTree(server.lastBody("/api/index")).get("threads").asInt());
    }
  }

  @Test
  void indexWithJsonPrintsOnlyTheRawResponse() throws Exception {
    String body = "{\"status\":\"ok\",\"documents_indexed\":10,\"time_ms\":100}";
    try (FakeHttpServer server = new FakeHttpServer().on("/api/index", 200, body).start()) {
      Result result = run("--index", "--json", "--url", server.url());
      assertEquals(body + "\n", result.out());
      assertEquals("", result.err());
    }
  }

  @Test
  void http400ShowsTheMessageOfTheServer() throws Exception {
    String body = "{\"status\":\"error\",\"message\":\"threads must be between 1 and 1024\"}";
    try (FakeHttpServer server = new FakeHttpServer().on("/api/index", 400, body).start()) {
      Result result = run("--index", "5000", "--url", server.url());
      assertEquals(1, result.code());
      assertEquals("", result.out());
      assertTrue(result.err().contains("HTTP 400"), result.err());
      assertTrue(result.err().contains("threads must be between 1 and 1024"), result.err());
    }
  }

  @Test
  void http409ShowsTheMessageOfTheServer() throws Exception {
    String body = "{\"status\":\"error\",\"message\":\"Indexing is already running\"}";
    try (FakeHttpServer server = new FakeHttpServer().on("/api/index", 409, body).start()) {
      Result result = run("--index", "--url", server.url());
      assertEquals(1, result.code());
      assertTrue(result.err().contains("HTTP 409"), result.err());
      assertTrue(result.err().contains("Indexing is already running"), result.err());
    }
  }

  @Test
  void http500ShowsTheMessageOfTheServer() throws Exception {
    String body = "{\"status\":\"error\",\"message\":\"Cannot read the data directory\"}";
    try (FakeHttpServer server = new FakeHttpServer().on("/api/index", 500, body).start()) {
      Result result = run("--index", "--url", server.url());
      assertEquals(1, result.code());
      assertTrue(result.err().contains("HTTP 500"), result.err());
      assertTrue(result.err().contains("Cannot read the data directory"), result.err());
    }
  }

  @Test
  void anErrorBodyThatIsNotJsonIsShownAsText() throws Exception {
    try (FakeHttpServer server = new FakeHttpServer().on("/api/search", 500, "boom").start()) {
      Result result = run("--rest", "--url", server.url(), "q");
      assertEquals(1, result.code());
      assertTrue(result.err().contains("HTTP 500): boom"), result.err());
    }
  }

  @Test
  void anAnswerThatIsNotJsonIsReportedClearly() throws Exception {
    try (FakeHttpServer server = new FakeHttpServer().on("/api/stats", 200, "<html>").start()) {
      Result result = run("--stats", "--url", server.url());
      assertEquals(1, result.code());
      assertTrue(result.err().contains("не у форматі JSON"), result.err());
    }
  }

  @Test
  void unavailableServerGivesAClearMessageAndExitCode1ForEveryCommand() throws Exception {
    String url = deadUrl();
    String[][] commands = {
      {"--rest", "q"}, {"--health"}, {"--stats"}, {"--index"}, {"white whale"}
    };
    for (String[] command : commands) {
      String[] args = new String[command.length + 2];
      args[0] = "--url";
      args[1] = url;
      System.arraycopy(command, 0, args, 2, command.length);

      Result result = run(args);

      assertEquals(1, result.code(), String.join(" ", command) + ": " + result.err());
      assertTrue(result.err().contains("Сервер недоступний"), result.err());
      assertTrue(result.err().contains(url), "the message names the address: " + result.err());
      assertFalse(result.err().contains("Exception"), "no raw exception text: " + result.err());
    }
  }

  @Test
  void aSlowServerEndsWithATimeoutMessage() throws Exception {
    try (FakeHttpServer server =
        new FakeHttpServer()
            .on(
                "/api/health",
                (method, body, responder) -> {
                  Thread.sleep(3_000);
                  responder.send(200, "{\"status\":\"ok\",\"ollama\":true}");
                })
            .start()) {
      long start = System.nanoTime();
      Result result = run("--health", "--timeout", "1", "--url", server.url());
      long seconds = (System.nanoTime() - start) / 1_000_000_000L;

      assertEquals(1, result.code());
      assertTrue(result.err().contains("таймаут"), result.err());
      assertTrue(seconds < 3, "must stop after about 1 s, took " + seconds);
    }
  }

  @Test
  void emptyQueryGivesAHintAndExitCode2WithoutCallingTheServer() throws Exception {
    try (FakeHttpServer server = new FakeHttpServer().on("/api/search", 200, RESULTS).start()) {
      for (String[] args : new String[][] {{}, {""}, {"   "}, {"--rest"}}) {
        String[] full = new String[args.length + 2];
        full[0] = "--url";
        full[1] = server.url();
        System.arraycopy(args, 0, full, 2, args.length);

        Result result = run(full);

        assertEquals(2, result.code());
        assertTrue(result.err().contains("Порожній запит"), result.err());
        assertTrue(result.err().contains("Використання:"), result.err());
        assertEquals("", result.out());
      }
      assertEquals(0, server.requestCount());
    }
  }

  @Test
  void unknownOptionGivesExitCode2() {
    Result result = run("--bogus", "q");
    assertEquals(2, result.code());
    assertTrue(result.err().contains("--bogus"), result.err());
  }

  @Test
  void badServerAddressGivesExitCode2() {
    Result result = run("--url", "ftp://x", "--health");
    assertEquals(2, result.code());
    assertTrue(result.err().contains("Некоректна адреса"), result.err());
  }

  @Test
  void helpListsTheCommandsAndExitsWith0() {
    Result result = run("--help");
    assertEquals(0, result.code());
    for (String part :
        new String[] {"--health", "--stats", "--index", "--rest", "--json", "--url", "--timeout"}) {
      assertTrue(result.out().contains(part), "help must mention " + part);
    }
    assertEquals("", result.err());
  }

  @Test
  void searchUrlFromTheEnvironmentIsUsedAndTheOptionWins() throws Exception {
    String health = "{\"status\":\"ok\",\"ollama\":true}";
    try (FakeHttpServer fromEnv = new FakeHttpServer().on("/api/health", 200, health).start();
        FakeHttpServer fromOption = new FakeHttpServer().on("/api/health", 200, health).start()) {

      Result viaEnv = run("", Map.of("SEARCH_URL", fromEnv.url()), "--health");
      assertEquals(0, viaEnv.code(), viaEnv.err());
      assertEquals(1, fromEnv.requestCount());

      Result viaOption =
          run("", Map.of("SEARCH_URL", fromEnv.url()), "--health", "--url", fromOption.url());
      assertEquals(0, viaOption.code(), viaOption.err());
      assertEquals(1, fromOption.requestCount());
      assertEquals(1, fromEnv.requestCount(), "--url must win over SEARCH_URL");
    }
  }

  @Test
  void withoutUrlOrEnvironmentTheDefaultIsLocalhost8080() throws Exception {
    Result result = run("--health", "--timeout", "2");
    if (result.code() == 1) {
      assertTrue(result.err().contains("http://localhost:8080"), result.err());
    } else {
      assertNull(null);
    }
  }
}
