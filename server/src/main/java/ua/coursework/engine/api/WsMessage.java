package ua.coursework.engine.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * One message that the server sends over the WebSocket: {@code {"stage": "...", "data": {...}}}.
 * The {@code data} field is left out when it is null, so the last message is just {@code
 * {"stage":"done"}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WsMessage(@JsonProperty("stage") String stage, @JsonProperty("data") Object data) {

  public static final String QUERY_EXPANSION = "query_expansion";
  public static final String KEYWORD_SEARCH = "keyword_search";
  public static final String ERROR = "error";
  public static final String DONE = "done";

  /** The "data" of the query_expansion stage. */
  public record Expansion(
      @JsonProperty("original") String original,
      @JsonProperty("expanded_terms") List<String> expandedTerms) {}

  /** The "data" of the keyword_search stage. */
  public record Search(
      @JsonProperty("results") List<SearchResult> results,
      @JsonProperty("time_ms") long timeMs) {}

  /** The "data" of the error stage. */
  public record Failure(@JsonProperty("message") String message) {}

  public static WsMessage queryExpansion(String original, List<String> expandedTerms) {
    return new WsMessage(QUERY_EXPANSION, new Expansion(original, expandedTerms));
  }

  public static WsMessage keywordSearch(List<SearchResult> results, long timeMs) {
    return new WsMessage(KEYWORD_SEARCH, new Search(results, timeMs));
  }

  public static WsMessage error(String message) {
    return new WsMessage(ERROR, new Failure(message));
  }

  public static WsMessage done() {
    return new WsMessage(DONE, null);
  }
}
