package ua.coursework.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ua.coursework.cli.CliTestSupport.deadUrl;
import static ua.coursework.cli.CliTestSupport.run;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ua.coursework.cli.CliTestSupport.Result;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CliWsTest {

  private static final String EXPANSION =
      "{\"stage\":\"query_expansion\",\"data\":{\"original\":\"white whale\","
          + "\"expanded_terms\":[\"moby\",\"dick\"]}}";
  private static final String EMPTY_EXPANSION =
      "{\"stage\":\"query_expansion\",\"data\":{\"original\":\"q\",\"expanded_terms\":[]}}";
  private static final String SEARCH =
      "{\"stage\":\"keyword_search\",\"data\":{\"results\":[{\"doc_id\":\"a.txt#1\","
          + "\"score\":1.0,\"snippet\":\"the white whale\"}],\"time_ms\":12}}";
  private static final String DONE = "{\"stage\":\"done\"}";

  @Test
  void stagesAreShownOneByOneAndTheSearchEndsWithDone() throws Exception {
    try (FakeWsServer server = new FakeWsServer().send(EXPANSION).send(SEARCH).send(DONE).start()) {
      Result result = run("--url", server.url(), "white whale");

      assertEquals(0, result.code(), result.err());
      assertEquals(
          "Розширення: moby, dick\n"
              + "1. a.txt#1  1.0000\n"
              + "    the white whale\n"
              + "Час пошуку: 12 мс\n"
              + "Готово.\n",
          result.out());
      assertEquals("", result.err());
      assertEquals(List.of("{\"query\":\"white whale\"}"), server.received());
    }
  }

  @Test
  void theFirstStageIsPrintedBeforeTheSecondOneExists() throws Exception {
    // stages must print as they arrive
    try (FakeWsServer server =
        new FakeWsServer().send(EXPANSION).sleep(700).send(SEARCH).send(DONE).start()) {
      Result result = run("--url", server.url(), "white whale");

      assertEquals(0, result.code(), result.err());
      long expansion = result.recording().timeOfLineContaining("Розширення");
      long results = result.recording().timeOfLineContaining("1. a.txt#1");
      long done = result.recording().timeOfLineContaining("Готово.");
      assertTrue(expansion > 0 && results > 0 && done > 0);
      long gapMillis = (results - expansion) / 1_000_000;
      assertTrue(gapMillis >= 500, "stages must appear as they arrive, gap was " + gapMillis + " ms");
    }
  }

  @Test
  void emptyExpansionPrintsTheFallbackLine() throws Exception {
    try (FakeWsServer server =
        new FakeWsServer().send(EMPTY_EXPANSION).send(SEARCH).send(DONE).start()) {
      Result result = run("--url", server.url(), "q");
      assertEquals(0, result.code(), result.err());
      assertTrue(
          result.out().startsWith("Розширення: немає (Ollama недоступна)\n"), result.out());
    }
  }

  @Test
  void cyrillicQueryIsSentAsUtf8() throws Exception {
    try (FakeWsServer server = new FakeWsServer().send(EMPTY_EXPANSION).send(SEARCH).send(DONE).start()) {
      Result result = run("--url", server.url(), "біла", "вага");
      assertEquals(0, result.code(), result.err());
      assertEquals(List.of("{\"query\":\"біла вага\"}"), server.received());
    }
  }

  @Test
  void jsonModePrintsEveryMessageAsItWasSent() throws Exception {
    try (FakeWsServer server = new FakeWsServer().send(EXPANSION).send(SEARCH).send(DONE).start()) {
      Result result = run("--json", "--url", server.url(), "white whale");
      assertEquals(0, result.code(), result.err());
      assertEquals(EXPANSION + "\n" + SEARCH + "\n" + DONE + "\n", result.out());
    }
  }

  @Test
  void anErrorStageIsReportedAndGivesExitCode1() throws Exception {
    String error = "{\"stage\":\"error\",\"data\":{\"message\":\"query must not be empty\"}}";
    try (FakeWsServer server = new FakeWsServer().send(error).send(DONE).start()) {
      Result result = run("--url", server.url(), "q");

      assertEquals(1, result.code());
      assertTrue(result.err().contains("Помилка: query must not be empty"), result.err());
      assertTrue(result.out().contains("Готово."), "the search still ends with done");
    }
  }

  @Test
  void aServerThatNeverFinishesEndsWithATimeoutAfterTheShownStages() throws Exception {
    try (FakeWsServer server = new FakeWsServer().send(EXPANSION).hang().start()) {
      long start = System.nanoTime();
      Result result = run("--timeout", "1", "--url", server.url(), "white whale");
      long seconds = (System.nanoTime() - start) / 1_000_000_000L;

      assertEquals(1, result.code());
      assertTrue(result.err().contains("Таймаут"), result.err());
      assertEquals("Розширення: moby, dick\n", result.out(), "what arrived was already printed");
      assertTrue(seconds < 10, "took " + seconds + " s");
    }
  }

  @Test
  void aConnectionClosedBeforeDoneIsAnError() throws Exception {
    try (FakeWsServer server = new FakeWsServer().send(EXPANSION).closeConnection().start()) {
      Result result = run("--url", server.url(), "white whale");

      assertEquals(1, result.code());
      assertTrue(result.err().contains("закрив з'єднання"), result.err());
      assertTrue(result.out().contains("Розширення: moby, dick"));
    }
  }

  @Test
  void aServerWithoutWebSocketGivesAHintToTryRest() throws Exception {
    try (FakeWsServer server = new FakeWsServer().refuseWith(404).start()) {
      Result result = run("--url", server.url(), "q");

      assertEquals(1, result.code());
      assertTrue(result.err().contains("HTTP 404"), result.err());
      assertTrue(result.err().contains("--rest"), result.err());
    }
  }

  @Test
  void anUnreachableServerGivesTheUnavailableMessage() throws Exception {
    Result result = run("--url", deadUrl(), "q");
    assertEquals(1, result.code());
    assertTrue(result.err().contains("Сервер недоступний"), result.err());
    assertFalse(result.err().contains("Exception"), result.err());
    assertEquals("", result.out());
  }

  @Test
  void aWsAddressIsAcceptedForTheUrlOption() throws Exception {
    try (FakeWsServer server = new FakeWsServer().send(EMPTY_EXPANSION).send(SEARCH).send(DONE).start()) {
      Result result = run("--url", server.url().replace("http://", "ws://"), "q");
      assertEquals(0, result.code(), result.err());
    }
  }
}
