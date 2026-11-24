package ua.coursework.engine.search;

import java.util.List;

public record QueryExpansion(String original, List<String> originalTerms, List<String> expandedTerms) {

  public QueryExpansion {
    originalTerms = List.copyOf(originalTerms);
    expandedTerms = List.copyOf(expandedTerms);
  }
}
