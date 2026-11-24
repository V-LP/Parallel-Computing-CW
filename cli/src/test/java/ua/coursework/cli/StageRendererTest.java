package ua.coursework.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StageRendererTest {

  private static final String EXPANSION =
      "{\"stage\":\"query_expansion\",\"data\":{\"original\":\"white whale\","
          + "\"expanded_terms\":[\"moby\",\"dick\"]}}";
  private static final String EMPTY_EXPANSION =
      "{\"stage\":\"query_expansion\",\"data\":{\"original\":\"q\",\"expanded_terms\":[]}}";
  private static final String SEARCH =
      "{\"stage\":\"keyword_search\",\"data\":{\"results\":[{\"doc_id\":\"a.txt#1\","
          + "\"score\":1.0,\"snippet\":\"the white whale\"}],\"time_ms\":12}}";
  private static final String DONE = "{\"stage\":\"done\"}";

  private ByteArrayOutputStream outBytes;
  private ByteArrayOutputStream errBytes;
  private PrintStream out;
  private PrintStream err;

  @BeforeEach
  void setUp() {
    outBytes = new ByteArrayOutputStream();
    errBytes = new ByteArrayOutputStream();
    out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
  }

  private String out() {
    return outBytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
  }

  private String err() {
    return errBytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
  }

  @Test
  void everyStageIsPrintedAsSoonAsItArrives() {
    StageRenderer renderer = new StageRenderer(out, err, false);

    assertFalse(renderer.accept(EXPANSION));
    assertEquals("Розширення: moby, dick\n", out());

    assertFalse(renderer.accept(SEARCH));
    assertEquals(
        "Розширення: moby, dick\n"
            + "1. a.txt#1  1.0000\n"
            + "    the white whale\n"
            + "Час пошуку: 12 мс\n",
        out());

    assertTrue(renderer.accept(DONE));
    assertTrue(out().endsWith("Час пошуку: 12 мс\nГотово.\n"));
    assertFalse(renderer.failed());
    assertEquals("", err());
  }

  @Test
  void emptyExpansionPrintsTheFallbackLine() {
    new StageRenderer(out, err, false).accept(EMPTY_EXPANSION);
    assertEquals("Розширення: немає (Ollama недоступна)\n", out());
  }

  @Test
  void errorStageGoesToStderrAndMarksFailure() {
    StageRenderer renderer = new StageRenderer(out, err, false);
    assertFalse(renderer.accept("{\"stage\":\"error\",\"data\":{\"message\":\"query is empty\"}}"));
    assertTrue(renderer.failed());
    assertEquals("Помилка: query is empty\n", err());
    assertEquals("", out());
    assertTrue(renderer.accept(DONE)); // "done" still follows the error
  }

  @Test
  void rawJsonModePrintsTheMessagesUnchanged() {
    StageRenderer renderer = new StageRenderer(out, err, true);
    renderer.accept(EXPANSION);
    renderer.accept(SEARCH);
    assertTrue(renderer.accept(DONE));
    assertEquals(EXPANSION + "\n" + SEARCH + "\n" + DONE + "\n", out());
    assertFalse(renderer.failed());
  }

  @Test
  void rawJsonModeStillReportsAnErrorStageAsFailure() {
    StageRenderer renderer = new StageRenderer(out, err, true);
    String error = "{\"stage\":\"error\",\"data\":{\"message\":\"x\"}}";
    renderer.accept(error);
    assertTrue(renderer.failed());
    assertEquals(error + "\n", out());
  }

  @Test
  void anUnknownStageIsIgnored() {
    StageRenderer renderer = new StageRenderer(out, err, false);
    assertFalse(renderer.accept("{\"stage\":\"reranking\",\"data\":{}}"));
    assertFalse(renderer.failed());
    assertEquals("", out());
  }

  @Test
  void aMessageThatIsNotJsonIsAFailure() {
    StageRenderer renderer = new StageRenderer(out, err, false);
    assertFalse(renderer.accept("not json"));
    assertTrue(renderer.failed());
    assertTrue(err().contains("не JSON"));
  }

  @Test
  void aMessageWithoutStageIsAFailure() {
    StageRenderer renderer = new StageRenderer(out, err, false);
    assertFalse(renderer.accept("{\"data\":{}}"));
    assertTrue(renderer.failed());
  }

  @Test
  void emptyResultsPrintNothingFoundAndTime() {
    StageRenderer renderer = new StageRenderer(out, err, false);
    renderer.accept("{\"stage\":\"keyword_search\",\"data\":{\"results\":[],\"time_ms\":2}}");
    assertEquals("Нічого не знайдено.\nЧас пошуку: 2 мс\n", out());
  }
}
