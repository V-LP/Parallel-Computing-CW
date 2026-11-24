package ua.coursework.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResultFormatterTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static String print(String resultsJson, long timeMs) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    JsonNode results = JSON.readTree(resultsJson);
    ResultFormatter.printResults(out, results, timeMs);
    return bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
  }

  @Test
  void expansionLineListsTheTerms() {
    assertEquals(
        "Розширення: moby, dick, whale", ResultFormatter.expansionLine(List.of("moby", "dick", "whale")));
  }

  @Test
  void emptyExpansionSaysThatOllamaIsUnavailable() {
    assertEquals("Розширення: немає (Ollama недоступна)", ResultFormatter.expansionLine(List.of()));
  }

  @Test
  void resultsAreNumberedWithScoreAndTheSnippetBelow() throws Exception {
    String text =
        print(
            "[{\"doc_id\":\"a/moby.txt#3\",\"score\":1.0,\"snippet\":\"call me Ishmael\"},"
                + "{\"doc_id\":\"b.txt\",\"score\":0.5,\"snippet\":\"second\"}]",
            12);
    assertEquals(
        "1. a/moby.txt#3  1.0000\n"
            + "    call me Ishmael\n"
            + "2. b.txt  0.5000\n"
            + "    second\n"
            + "Час пошуку: 12 мс\n",
        text);
  }

  @Test
  void scoreIsPrintedWithFourDecimalsAndADot() throws Exception {
    String text = print("[{\"doc_id\":\"d\",\"score\":0.9512,\"snippet\":\"\"}]", 1);
    assertTrue(text.startsWith("1. d  0.9512\n"), text);
  }

  @Test
  void aResultWithoutSnippetHasNoSnippetLine() throws Exception {
    assertEquals(
        "1. d  1.0000\nЧас пошуку: 3 мс\n", print("[{\"doc_id\":\"d\",\"score\":1.0,\"snippet\":\"\"}]", 3));
  }

  @Test
  void noResultsGiveAMessageAndStillTheTime() throws Exception {
    assertEquals("Нічого не знайдено.\nЧас пошуку: 0 мс\n", print("[]", 0));
  }

  @Test
  void longSnippetIsWrappedSoNoLineIsWiderThanTheLimit() throws Exception {
    String snippet = ("word ".repeat(60)).trim();
    String text = print("[{\"doc_id\":\"d\",\"score\":1.0,\"snippet\":\"" + snippet + "\"}]", 1);
    int snippetLines = 0;
    for (String line : text.split("\n")) {
      assertTrue(line.length() <= ResultFormatter.LINE_WIDTH, "too wide: " + line);
      if (line.startsWith("    word")) {
        snippetLines++;
      }
    }
    assertTrue(snippetLines >= 3, "the snippet must be on several lines: " + text);
  }

  @Test
  void wrapBreaksOnlyAtSpacesAndFillsLinesGreedily() {
    assertEquals(List.of("aaa bbb", "ccc ddd", "eee"), ResultFormatter.wrap("aaa bbb ccc ddd eee", 7));
    assertEquals(List.of("aaa bbb ccc"), ResultFormatter.wrap("aaa bbb ccc", 11));
  }

  @Test
  void wrapTreatsAnyWhiteSpaceAsOneSpace() {
    assertEquals(List.of("a b c"), ResultFormatter.wrap("  a \n\t b   c \r\n", 20));
  }

  @Test
  void wrapCutsAWordLongerThanTheWidth() {
    assertEquals(List.of("ab", "cd", "ef", "x"), ResultFormatter.wrap("abcdef x", 2));
    assertEquals(List.of("abc", "def", "g"), ResultFormatter.wrap("abcdefg", 3));
  }

  @Test
  void wrapOfBlankTextIsEmpty() {
    assertTrue(ResultFormatter.wrap("", 10).isEmpty());
    assertTrue(ResultFormatter.wrap("   \n ", 10).isEmpty());
    assertTrue(ResultFormatter.wrap(null, 10).isEmpty());
  }

  @Test
  void wrapKeepsCyrillicText() {
    assertEquals(List.of("біла вага", "і море"), ResultFormatter.wrap("біла вага і море", 10));
  }

  @Test
  void wrapRejectsANonPositiveWidth() {
    assertThrows(IllegalArgumentException.class, () -> ResultFormatter.wrap("a", 0));
  }
}
