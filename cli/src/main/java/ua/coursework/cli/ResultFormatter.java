package ua.coursework.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ResultFormatter {

  public static final int LINE_WIDTH = 100;

  private static final String INDENT = "    ";

  private ResultFormatter() {}

  public static String expansionLine(List<String> terms) {
    if (terms.isEmpty()) {
      return "Розширення: немає (Ollama недоступна)";
    }
    return "Розширення: " + String.join(", ", terms);
  }

  public static void printResults(PrintStream out, JsonNode results, long timeMs) {
    if (results == null || !results.isArray() || results.isEmpty()) {
      out.println("Нічого не знайдено.");
    } else {
      int number = 1;
      for (JsonNode result : results) {
        out.println(resultLine(number++, result));
        for (String line : wrap(textOf(result, "snippet"), LINE_WIDTH - INDENT.length())) {
          out.println(INDENT + line);
        }
      }
    }
    out.println("Час пошуку: " + timeMs + " мс");
  }

  static String resultLine(int number, JsonNode result) {
    JsonNode score = result.get("score");
    String scoreText = score != null && score.isNumber() ? String.format(Locale.ROOT, "%.4f", score.asDouble()) : "?";
    return number + ". " + textOf(result, "doc_id") + "  " + scoreText;
  }

  private static String textOf(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? "" : value.asText();
  }

  public static List<String> wrap(String text, int width) {
    if (width < 1) {
      throw new IllegalArgumentException("width must be positive");
    }
    List<String> lines = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return lines;
    }
    StringBuilder line = new StringBuilder();
    for (String word : text.trim().split("\\s+")) {
      while (word.length() > width) {
        if (line.length() > 0) {
          lines.add(line.toString());
          line.setLength(0);
        }
        lines.add(word.substring(0, width));
        word = word.substring(width);
      }
      if (line.length() > 0 && line.length() + 1 + word.length() > width) {
        lines.add(line.toString());
        line.setLength(0);
      }
      if (line.length() > 0) {
        line.append(' ');
      }
      line.append(word);
    }
    if (line.length() > 0) {
      lines.add(line.toString());
    }
    return lines;
  }
}
