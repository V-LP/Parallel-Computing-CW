package ua.coursework.engine.index;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Splits text into index terms.
 *
 * <p>Rules: lower case; a term is a run of letters or digits (everything else separates terms);
 * terms shorter than 2 or longer than 40 characters are dropped; English stop words are dropped.
 * Project Gutenberg license text is skipped: reading starts after the {@code *** START OF ...}
 * marker line (if there is one near the top) and stops at the {@code *** END OF ...} marker line.
 * Without markers the whole text is used.
 *
 * <p>The tokenizer has no mutable state, so one instance can be used by many threads.
 */
public final class Tokenizer {

  static final int MIN_LENGTH = 2;
  static final int MAX_LENGTH = 40;

  /** The START marker is searched only in the first lines, because the header is short. */
  static final int HEADER_SCAN_LINES = 300;

  private static final String STOP_WORDS_RESOURCE = "/stopwords.txt";

  private final Set<String> stopWords; // never modified after construction

  /** Creates a tokenizer with the built-in stop word list (resource {@code stopwords.txt}). */
  public Tokenizer() {
    this(loadStopWords());
  }

  public Tokenizer(Set<String> stopWords) {
    this.stopWords = Set.copyOf(stopWords);
  }

  /** Tokenizes a whole text (Gutenberg header and footer are skipped). Handy for queries. */
  public List<String> tokenize(String text) {
    List<String> tokens = new ArrayList<>();
    try {
      tokenize(new BufferedReader(new StringReader(text)), tokens::add);
    } catch (IOException e) {
      throw new IllegalStateException("Reading from a string cannot fail", e); // not reachable
    }
    return tokens;
  }

  /**
   * Reads the text line by line (never the whole text at once) and passes every kept term to
   * {@code sink}.
   */
  public void tokenize(BufferedReader reader, Consumer<String> sink) throws IOException {
    forEachBodyLine(
        reader,
        line -> {
          tokenizeLine(line, sink);
          return true;
        });
  }

  /**
   * Passes the lines of the book body (without the Gutenberg header and footer) to {@code
   * handler}, one by one, until the handler returns false or the body ends. The snippet builder
   * uses it too, so snippets never come from the license text.
   */
  public void forEachBodyLine(BufferedReader reader, Predicate<String> handler)
      throws IOException {
    // Step 1: read the first lines and remember the position of the START marker. If the header
    // has several such lines (old books), the last one wins.
    List<String> head = new ArrayList<>();
    int startMarker = -1;
    boolean endOfInput = false;
    while (head.size() < HEADER_SCAN_LINES) {
      String line = reader.readLine();
      if (line == null) {
        endOfInput = true;
        break;
      }
      head.add(line);
      if (isMarker(line, "START OF")) {
        startMarker = head.size() - 1;
      }
    }

    // Step 2: the buffered lines after the START marker (or all of them without one).
    for (int i = startMarker + 1; i < head.size(); i++) {
      String line = head.get(i);
      if (isMarker(line, "END OF") || !handler.test(line)) {
        return;
      }
    }

    // Step 3: the rest of the text, up to the END marker.
    if (!endOfInput) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (isMarker(line, "END OF") || !handler.test(line)) {
          return;
        }
      }
    }
  }

  /** Splits one line into terms. */
  void tokenizeLine(String line, Consumer<String> sink) {
    String s = line.toLowerCase(Locale.ROOT);
    int start = -1;
    for (int i = 0; i <= s.length(); i++) {
      boolean inTerm = i < s.length() && Character.isLetterOrDigit(s.charAt(i));
      if (inTerm) {
        if (start < 0) {
          start = i;
        }
      } else if (start >= 0) {
        emit(s, start, i, sink);
        start = -1;
      }
    }
  }

  private void emit(String s, int start, int end, Consumer<String> sink) {
    int length = end - start;
    if (length < MIN_LENGTH || length > MAX_LENGTH) {
      return;
    }
    String term = s.substring(start, end);
    if (!stopWords.contains(term)) {
      sink.accept(term);
    }
  }

  /** A marker line looks like "*** START OF THE PROJECT GUTENBERG EBOOK ... ***". */
  private static boolean isMarker(String line, String kind) {
    String trimmed = line.trim();
    return trimmed.startsWith("***") && trimmed.toUpperCase(Locale.ROOT).contains(kind);
  }

  private static Set<String> loadStopWords() {
    try (InputStream in = Tokenizer.class.getResourceAsStream(STOP_WORDS_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + STOP_WORDS_RESOURCE);
      }
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      Set<String> words = new java.util.HashSet<>();
      String line;
      while ((line = reader.readLine()) != null) {
        String word = line.trim().toLowerCase(Locale.ROOT);
        if (!word.isEmpty() && !word.startsWith("#")) {
          words.add(word);
        }
      }
      return words;
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read " + STOP_WORDS_RESOURCE, e);
    }
  }
}
