package ua.coursework.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Prints the WebSocket messages of the server one by one, at the moment each arrives. It does not
 * touch the network, so it is easy to test with plain strings.
 */
public final class StageRenderer {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final PrintStream out;
  private final PrintStream err;
  private final boolean rawJson;
  private boolean failed;

  public StageRenderer(PrintStream out, PrintStream err, boolean rawJson) {
    this.out = out;
    this.err = err;
    this.rawJson = rawJson;
  }

  /**
   * Handles one message.
   *
   * @return true after the "done" message (the search is finished)
   */
  public boolean accept(String message) {
    if (rawJson) {
      out.println(message);
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(message);
    } catch (Exception e) {
      failed = true;
      err.println("Сервер надіслав не JSON: " + abbreviate(message));
      return false;
    }
    JsonNode stage = root == null ? null : root.get("stage");
    if (stage == null || !stage.isTextual()) {
      failed = true;
      err.println("Повідомлення сервера без поля stage: " + abbreviate(message));
      return false;
    }
    JsonNode data = root.get("data");
    switch (stage.asText()) {
      case "query_expansion" -> {
        if (!rawJson) {
          out.println(ResultFormatter.expansionLine(termsOf(data)));
        }
      }
      case "keyword_search" -> {
        if (!rawJson) {
          long timeMs = data != null && data.has("time_ms") ? data.get("time_ms").asLong() : 0;
          ResultFormatter.printResults(out, data == null ? null : data.get("results"), timeMs);
        }
      }
      case "error" -> {
        failed = true;
        if (!rawJson) {
          JsonNode text = data == null ? null : data.get("message");
          err.println("Помилка: " + (text == null ? "невідома помилка сервера" : text.asText()));
        }
      }
      case "done" -> {
        if (!rawJson) {
          out.println("Готово.");
        }
        return true;
      }
      default -> {
        // A stage that this client does not know: ignore it (the server may be newer).
      }
    }
    return false;
  }

  /** True if the server reported an error or sent something that is not understood. */
  public boolean failed() {
    return failed;
  }

  private static List<String> termsOf(JsonNode data) {
    List<String> terms = new ArrayList<>();
    JsonNode array = data == null ? null : data.get("expanded_terms");
    if (array != null && array.isArray()) {
      for (JsonNode term : array) {
        terms.add(term.asText());
      }
    }
    return terms;
  }

  private static String abbreviate(String text) {
    return text.length() <= 200 ? text : text.substring(0, 200) + "...";
  }
}
