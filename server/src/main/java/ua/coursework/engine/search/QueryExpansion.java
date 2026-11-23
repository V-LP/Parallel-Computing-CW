package ua.coursework.engine.search;

import java.util.List;

/**
 * The first stage of an expanded search: the query terms and the extra terms from the LLM. The
 * expanded list is empty when the LLM was not available (fallback to the original query).
 */
public record QueryExpansion(String original, List<String> originalTerms, List<String> expandedTerms) {

  public QueryExpansion {
    originalTerms = List.copyOf(originalTerms);
    expandedTerms = List.copyOf(expandedTerms);
  }
}
