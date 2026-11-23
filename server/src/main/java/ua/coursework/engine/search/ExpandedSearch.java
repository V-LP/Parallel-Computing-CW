package ua.coursework.engine.search;

import java.util.List;

/** Result of {@link SearchService#searchWithExpansion}: what was expanded and what was found. */
public record ExpandedSearch(QueryExpansion expansion, List<SearchHit> hits) {

  public ExpandedSearch {
    hits = List.copyOf(hits);
  }
}
