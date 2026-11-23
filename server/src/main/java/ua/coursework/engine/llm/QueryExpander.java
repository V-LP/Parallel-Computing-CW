package ua.coursework.engine.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.coursework.engine.index.Tokenizer;

/**
 * Asks the LLM for extra keywords and synonyms of a search query.
 *
 * <p>{@link #expand} never throws: if the LLM is busy, down, too slow or answers nonsense, the
 * result is an empty list and the caller simply searches with the original query (the "fallback").
 *
 * <p>Only one generation runs at a time (a {@link Semaphore} with one permit): a small local model
 * is slow and several parallel generations would only make every one of them slower. A request that
 * cannot get the permit within a short time gives up and falls back instead of queueing up.
 */
public final class QueryExpander {

  /** At most this many expansion terms are returned. */
  public static final int MAX_TERMS = 8;

  private static final Logger LOG = LoggerFactory.getLogger(QueryExpander.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long DEFAULT_WAIT_MILLIS = 5_000;
  private static final int DEFAULT_CACHE_SIZE = 256;
  /** In the comma/line fallback, an item with more words than this is a sentence, not a keyword. */
  private static final int MAX_WORDS_PER_ITEM = 4;

  private final TextGenerator generator;
  private final Tokenizer tokenizer;
  private final long permitWaitMillis;
  private final Semaphore llmGate = new Semaphore(1);
  private final Map<String, List<String>> cache; // LRU; every access is inside synchronized (cache)

  public QueryExpander(TextGenerator generator, Tokenizer tokenizer) {
    this(generator, tokenizer, DEFAULT_WAIT_MILLIS, DEFAULT_CACHE_SIZE);
  }

  /**
   * @param permitWaitMillis how long a call waits for the single generation permit
   * @param cacheSize number of remembered expansions; 0 turns the cache off
   */
  public QueryExpander(
      TextGenerator generator, Tokenizer tokenizer, long permitWaitMillis, int cacheSize) {
    this.generator = Objects.requireNonNull(generator, "generator");
    this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
    this.permitWaitMillis = permitWaitMillis;
    final int capacity = Math.max(0, cacheSize);
    this.cache =
        new LinkedHashMap<>(16, 0.75f, true) { // true = access order, so the eldest is the LRU
          private static final long serialVersionUID = 1L;

          @Override
          protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
            return size() > capacity;
          }
        };
  }

  /**
   * Returns up to {@value #MAX_TERMS} new terms related to the query, or an empty list when the
   * expansion is not possible. The terms are already tokenized (lower case, no stop words), are
   * not repeated and do not contain words that are already in the query.
   */
  public List<String> expand(String query) {
    if (query == null) {
      return List.of();
    }
    List<String> queryTerms = tokenizer.tokenize(query);
    if (queryTerms.isEmpty()) {
      return List.of();
    }
    String key = String.join(" ", queryTerms);

    synchronized (cache) {
      List<String> cached = cache.get(key);
      if (cached != null) {
        return cached;
      }
    }

    boolean acquired = false;
    try {
      acquired = llmGate.tryAcquire(permitWaitMillis, TimeUnit.MILLISECONDS);
      if (!acquired) {
        LOG.warn("Query expansion skipped: the model is busy with another request");
        return List.of();
      }
      String answer = generator.generate(buildPrompt(query));
      List<String> terms = parse(answer, queryTerms);
      if (terms.isEmpty()) {
        LOG.warn("Query expansion gave no usable terms for '{}'", query);
        return List.of();
      }
      synchronized (cache) {
        cache.put(key, terms);
      }
      return terms;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return List.of();
    } catch (OllamaException | RuntimeException e) {
      LOG.warn("Query expansion failed, using the original query only: {}", e.toString());
      return List.of();
    } finally {
      if (acquired) {
        llmGate.release();
      }
    }
  }

  /** The prompt sent to the model (English, because the documents are English). */
  static String buildPrompt(String query) {
    return "You are a search engine helper. Given a search query, list 5 to 8 related English "
        + "keywords and synonyms that help to find relevant books.\n"
        + "Reply with ONLY a JSON array of strings, for example [\"word1\", \"word2\"]. "
        + "No explanations, no other text.\n"
        + "Query: "
        + query
        + "\nJSON array:";
  }

  /**
   * Turns the model answer into expansion terms. First it tries to read a JSON array (also when the
   * model wrapped it in text or a code block). If that fails, the answer is split by commas, lines
   * and semicolons, and list markers and quotes are removed. Every item goes through the {@link
   * Tokenizer}; duplicates, stop words and words already in the query are dropped; at most {@value
   * #MAX_TERMS} terms are kept.
   */
  List<String> parse(String answer, List<String> queryTerms) {
    if (answer == null || answer.isBlank()) {
      return List.of();
    }
    List<String> items = parseJsonArray(answer);
    if (items == null) {
      items = splitPlainText(answer);
    }

    Set<String> exclude = new LinkedHashSet<>(queryTerms);
    Set<String> result = new LinkedHashSet<>();
    for (String item : items) {
      for (String token : tokenizer.tokenize(item)) {
        if (!exclude.contains(token)) {
          result.add(token);
        }
        if (result.size() == MAX_TERMS) {
          return List.copyOf(result);
        }
      }
    }
    return List.copyOf(result);
  }

  /** Returns the text items of the first "[...]" in the answer, or null if there is no JSON array. */
  private static List<String> parseJsonArray(String answer) {
    int start = answer.indexOf('[');
    int end = answer.lastIndexOf(']');
    if (start < 0 || end <= start) {
      return null;
    }
    try {
      JsonNode node = MAPPER.readTree(answer.substring(start, end + 1));
      if (node == null || !node.isArray()) {
        return null;
      }
      List<String> items = new ArrayList<>();
      for (JsonNode element : node) {
        if (element.isTextual()) {
          items.add(element.asText());
        }
      }
      return items;
    } catch (IOException e) {
      return null;
    }
  }

  private static List<String> splitPlainText(String answer) {
    List<String> items = new ArrayList<>();
    for (String part : answer.split("[,;\\n\\r]+")) {
      // Remove list markers ("-", "*", "1.", "2)") and brackets/quotes around the word.
      String item =
          part.replaceFirst("^\\s*(?:[-*•]|\\d+[.)])\\s+", "")
              .replaceAll("^[\\s\\[\\]\"'`]+|[\\s\\[\\]\"'`]+$", "");
      if (item.isEmpty()) {
        continue;
      }
      if (item.split("\\s+").length > MAX_WORDS_PER_ITEM) {
        continue; // a whole sentence ("Sure, here are some words:") is not a keyword
      }
      items.add(item);
    }
    return items;
  }
}
