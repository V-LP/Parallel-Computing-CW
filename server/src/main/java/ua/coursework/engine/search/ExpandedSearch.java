package ua.coursework.engine.search;

import java.util.List;

public record ExpandedSearch(QueryExpansion expansion, List<SearchHit> hits) {

  public ExpandedSearch {
    hits = List.copyOf(hits);
  }
}
