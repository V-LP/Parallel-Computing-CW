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

/**
 * Keyword search with TF-IDF ranking.
 *
 * <p>For a document d and the query terms t (OR semantics: one matching term is enough):
 *
 * <pre>
 *   tf(t, d)   = 1 + ln(freq(t, d))
 *   idf(t)     = ln(1 + N / df(t))
 *   score(d)   = coord(d) * sum_t( weight(t) * tf(t, d) * idf(t) ) / (1 + ln(length(d)))
 *   coord(d)   = weight of the query terms found in d / weight of all query terms found in the index
 * </pre>
 *
 * <p>The smoothed idf {@code ln(1 + N/df)} stays positive even when a term is in every document
 * (the plain {@code ln(N/df)} would be 0 and the term would not count at all). Dividing by {@code 1
 * + ln(length)} compensates for the fact that long books contain every word more often. {@code
 * coord} makes a document that matches more of the different query terms rank higher.
 *
 * <p>The service holds no mutable state. Every search reads one finished index (a snapshot taken at
 * the start) using only the read operations of the index, so searches do not block each other or an
 * indexing that is running in the background.
 */
public final class SearchService {

  /** Number of results returned when the caller does not choose. */
  public static final int DEFAULT_TOP_K = 10;

  /** Weight of the words typed by the user. */
  public static final double ORIGINAL_WEIGHT = 1.0;

  /** Weight of the words added by the LLM: they are guesses, so they count half as much. */
  public static final double EXPANDED_WEIGHT = 0.5;

  private final IndexManager indexManager;
  private final Tokenizer tokenizer;
  private final SnippetExtractor snippets;
  private final QueryExpander expander; // null: query expansion is switched off

  /** A service without query expansion ({@link #expandQuery} always finds nothing). */
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

  /**
   * Tokenizes the query with the same {@link Tokenizer} as the documents (lower case, stop words
   * removed) and searches. A query made only of stop words gives an empty result.
   */
  public List<SearchHit> search(String query, int topK) {
    Objects.requireNonNull(query, "query");
    return keywordSearch(tokenizer.tokenize(query), topK);
  }

  /**
   * Stage 1 of a search with expansion: tokenizes the query and asks the LLM for extra terms. It
   * never fails: when the LLM cannot help, the expanded list is empty. A WebSocket handler calls
   * this first, sends the expansion to the client, and then calls {@link #searchExpanded}.
   */
  public QueryExpansion expandQuery(String query) {
    Objects.requireNonNull(query, "query");
    List<String> originalTerms = tokenizer.tokenize(query);
    List<String> expanded =
        expander == null || originalTerms.isEmpty() ? List.of() : expander.expand(query);
    return new QueryExpansion(query, originalTerms, expanded);
  }

  /**
   * Stage 2: searches with the original terms (weight {@value #ORIGINAL_WEIGHT}) and the expanded
   * terms (weight {@value #EXPANDED_WEIGHT}). With no expanded terms this equals a plain search.
   */
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

  /** Both stages in one call: {@link #expandQuery} and then {@link #searchExpanded}. */
  public ExpandedSearch searchWithExpansion(String query, int topK) {
    QueryExpansion expansion = expandQuery(query);
    return new ExpandedSearch(expansion, searchExpanded(expansion, topK));
  }

  /** Searches for already tokenized terms, all with weight 1.0. */
  public List<SearchHit> keywordSearch(List<String> terms, int topK) {
    Objects.requireNonNull(terms, "terms");
    List<WeightedTerm> weighted = new ArrayList<>(terms.size());
    for (String term : terms) {
      weighted.add(WeightedTerm.of(term));
    }
    return keywordSearchWeighted(weighted, topK);
  }

  /**
   * Searches for terms with weights. The same term given twice counts once, with the larger weight.
   * Results are ordered by score (best first); equal scores are ordered by doc id (path), so the
   * result is always the same. Scores are divided by the best score (the best hit gets 1.0) and
   * rounded to 4 decimals.
   *
   * @throws IllegalArgumentException if topK is less than 1
   */
  public List<SearchHit> keywordSearchWeighted(List<WeightedTerm> terms, int topK) {
    Objects.requireNonNull(terms, "terms");
    if (topK < 1) {
      throw new IllegalArgumentException("topK must be >= 1, got " + topK);
    }

    // One snapshot for the whole query: a re-indexing that finishes meanwhile cannot mix two indexes.
    ConcurrentInvertedIndex index = indexManager.current().index();
    int documents = index.documentCount();
    if (documents == 0 || terms.isEmpty()) {
      return List.of();
    }

    Map<String, Double> weights = new LinkedHashMap<>(); // fixed order, so sums are repeatable
    for (WeightedTerm weighted : terms) {
      weights.merge(weighted.term().toLowerCase(Locale.ROOT), weighted.weight(), Math::max);
    }

    // Step 1: add up the TF-IDF contribution of every term to every document that contains it.
    double[] score = new double[documents];
    double[] matchedWeight = new double[documents];
    double totalWeight = 0;
    List<String> knownTerms = new ArrayList<>();
    for (Map.Entry<String, Double> entry : weights.entrySet()) {
      List<Posting> postings = index.getPostings(entry.getKey());
      if (postings.isEmpty()) {
        continue; // unknown term: matches nothing and must not lower anybody's coord
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

    // Step 2: keep only the best topK documents in a small heap (its head is the worst of them).
    Comparator<Candidate> worstFirst =
        (a, b) -> {
          int byScore = Double.compare(a.score(), b.score());
          if (byScore != 0) {
            return byScore;
          }
          // Equal scores: the larger path is "worse", so the smaller path stays in the top.
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

    // Step 3: take the heap apart (worst first) into a best-first array.
    Candidate[] best = new Candidate[heap.size()];
    for (int i = best.length - 1; i >= 0; i--) {
      best[i] = heap.poll();
    }

    // Step 4: scores relative to the best one; snippets are read only for these few documents.
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
