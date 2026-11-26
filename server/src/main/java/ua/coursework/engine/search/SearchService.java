package ua.coursework.engine.search;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import ua.coursework.engine.index.ConcurrentInvertedIndex;
import ua.coursework.engine.index.IndexManager;
import ua.coursework.engine.index.Posting;
import ua.coursework.engine.index.Tokenizer;
import ua.coursework.engine.llm.QueryExpander;

public final class SearchService {

  public static final int DEFAULT_TOP_K = 10;

  public static final double ORIGINAL_WEIGHT = 1.0;

  public static final double EXPANDED_WEIGHT = 0.5;

  private final IndexManager indexManager;
  private final Tokenizer tokenizer;
  private final SnippetExtractor snippets;
  private final QueryExpander expander; // null disables expansion

  public SearchService(IndexManager indexManager, Tokenizer tokenizer, Path dataDir) {
    this(indexManager, tokenizer, dataDir, null);
  }

  public SearchService(
      IndexManager indexManager, Tokenizer tokenizer, Path dataDir, QueryExpander expander) {
    this.expander = expander;
    this.indexManager = Objects.requireNonNull(indexManager, "indexManager");
    this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
    this.snippets = new SnippetExtractor(
            tokenizer, Objects.requireNonNull(dataDir, "dataDir"), indexManager.chunker());
  }

  public List<SearchHit> search(String query, int topK) {
    Objects.requireNonNull(query, "query");
    return keywordSearch(tokenizer.tokenize(query), topK);
  }

  public QueryExpansion expandQuery(String query) {
    Objects.requireNonNull(query, "query");
    List<String> originalTerms = tokenizer.tokenize(query);
    List<String> expanded =
        expander == null || originalTerms.isEmpty() ? List.of() : expander.expand(query);
    return new QueryExpansion(query, originalTerms, expanded);
  }

  public List<SearchHit> searchExpanded(QueryExpansion expansion, int topK) {
    Objects.requireNonNull(expansion, "expansion");
    List<WeightedTerm> terms = new ArrayList<>();
    for (String term : expansion.originalTerms()) {
      terms.add(new WeightedTerm(term, ORIGINAL_WEIGHT));
    }
    for (String term : expansion.expandedTerms()) {
      terms.add(new WeightedTerm(term, EXPANDED_WEIGHT));
    }
    return keywordSearchWeighted(terms, topK);
  }

  public ExpandedSearch searchWithExpansion(String query, int topK) {
    QueryExpansion expansion = expandQuery(query);
    return new ExpandedSearch(expansion, searchExpanded(expansion, topK));
  }

  public List<SearchHit> keywordSearch(List<String> terms, int topK) {
    Objects.requireNonNull(terms, "terms");
    List<WeightedTerm> weighted = new ArrayList<>(terms.size());
    for (String term : terms) {
      weighted.add(WeightedTerm.of(term));
    }
    return keywordSearchWeighted(weighted, topK);
  }

  public List<SearchHit> keywordSearchWeighted(List<WeightedTerm> terms, int topK) {
    Objects.requireNonNull(terms, "terms");
    if (topK < 1) {
      throw new IllegalArgumentException("topK must be >= 1, got " + topK);
    }

    // one snapshot per query
    ConcurrentInvertedIndex index = indexManager.current().index();
    int documents = index.documentCount();
    if (documents == 0 || terms.isEmpty()) {
      return List.of();
    }

    Map<String, Double> weights = new LinkedHashMap<>();
    for (WeightedTerm weighted : terms) {
      weights.merge(weighted.term().toLowerCase(Locale.ROOT), weighted.weight(), Math::max);
    }

    double[] score = new double[documents];
    double[] matchedWeight = new double[documents];
    double totalWeight = 0;
    List<String> knownTerms = new ArrayList<>();
    for (Map.Entry<String, Double> entry : weights.entrySet()) {
      List<Posting> postings = index.getPostings(entry.getKey());
      if (postings.isEmpty()) {
        continue; // unknown term matches nothing
      }
      double weight = entry.getValue();
      double idf = Math.log(1.0 + (double) documents / postings.size());
      totalWeight += weight;
      knownTerms.add(entry.getKey());
      for (Posting posting : postings) {
        int doc = posting.docId();
        score[doc] += weight * (1.0 + Math.log(posting.termFrequency())) * idf;
        matchedWeight[doc] += weight;
      }
    }
    if (knownTerms.isEmpty()) {
      return List.of();
    }

    Comparator<Candidate> worstFirst =
        (a, b) -> {
          int byScore = Double.compare(a.score(), b.score());
          if (byScore != 0) {
            return byScore;
          }
          // ties: larger path is worse
          return index.documentPath(b.docId()).compareTo(index.documentPath(a.docId()));
        };
    PriorityQueue<Candidate> heap = new PriorityQueue<>(Math.min(topK, documents) + 1, worstFirst);
    for (int doc = 0; doc < documents; doc++) {
      if (matchedWeight[doc] <= 0) {
        continue;
      }
      double coord = matchedWeight[doc] / totalWeight;
      double length = Math.max(1, index.documentLength(doc));
      Candidate candidate = new Candidate(doc, score[doc] * coord / (1.0 + Math.log(length)));
      if (heap.size() < topK) {
        heap.add(candidate);
      } else if (worstFirst.compare(candidate, heap.peek()) > 0) {
        heap.poll();
        heap.add(candidate);
      }
    }

    Candidate[] best = new Candidate[heap.size()];
    for (int i = best.length - 1; i >= 0; i--) {
      best[i] = heap.poll();
    }

    double bestScore = best[0].score();
    List<SearchHit> hits = new ArrayList<>(best.length);
    for (Candidate candidate : best) {
      String docId = index.documentPath(candidate.docId());
      hits.add(
          new SearchHit(
              docId, round4(candidate.score() / bestScore), snippets.extract(docId, knownTerms)));
    }
    return hits;
  }

  private static double round4(double value) {
    return Math.round(value * 10_000) / 10_000.0;
  }

  private record Candidate(int docId, double score) {}
}
