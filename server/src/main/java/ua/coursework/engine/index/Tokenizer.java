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

public final class Tokenizer {

  static final int MIN_LENGTH = 2;
  static final int MAX_LENGTH = 40;

  static final int HEADER_SCAN_LINES = 300;

  private static final String STOP_WORDS_RESOURCE = "/stopwords.txt";

  private final Set<String> stopWords;

  public Tokenizer() {
    this(loadStopWords());
  }

  public Tokenizer(Set<String> stopWords) {
    this.stopWords = Set.copyOf(stopWords);
  }

  public List<String> tokenize(String text) {
    List<String> tokens = new ArrayList<>();
    try {
      tokenize(new BufferedReader(new StringReader(text)), tokens::add);
    } catch (IOException e) {
      throw new IllegalStateException("Reading from a string cannot fail", e);
    }
    return tokens;
  }

  public void tokenize(BufferedReader reader, Consumer<String> sink) throws IOException {
    forEachBodyLine(
        reader,
        line -> {
          tokenizeLine(line, sink);
          return true;
        });
  }

  public void forEachBodyLine(BufferedReader reader, Predicate<String> handler)
      throws IOException {
    // last start marker wins
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

    for (int i = startMarker + 1; i < head.size(); i++) {
      String line = head.get(i);
      if (isMarker(line, "END OF") || !handler.test(line)) {
        return;
      }
    }

    if (!endOfInput) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (isMarker(line, "END OF") || !handler.test(line)) {
          return;
        }
      }
    }
  }

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
