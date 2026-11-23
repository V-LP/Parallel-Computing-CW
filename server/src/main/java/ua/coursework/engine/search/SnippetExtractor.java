package ua.coursework.engine.search;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.coursework.engine.index.Tokenizer;

/**
 * Builds a short text fragment (about 200 characters) around the first occurrence of a query term
 * in a book. Only the book body is read (the Gutenberg header and footer are skipped), line by
 * line, and reading stops as soon as the fragment is complete.
 */
final class SnippetExtractor {

  private static final Logger LOG = LoggerFactory.getLogger(SnippetExtractor.class);
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final String ELLIPSIS = "…";

  static final int SNIPPET_LENGTH = 200;

  private final Tokenizer tokenizer;
  private final Path dataDir;

  SnippetExtractor(Tokenizer tokenizer, Path dataDir) {
    this.tokenizer = tokenizer;
    this.dataDir = dataDir;
  }

  /** Returns the snippet, or an empty string if there is none or the file cannot be read. */
  String extract(String docId, Collection<String> terms) {
    if (terms.isEmpty()) {
      return "";
    }
    Pattern pattern = wholeWordPattern(terms);
    Path file = dataDir.resolve(docId);
    try {
      return readSnippet(file, pattern, StandardCharsets.UTF_8);
    } catch (MalformedInputException e) {
      // Not UTF-8: try again as ISO-8859-1, the same fallback as the indexer.
    } catch (IOException | RuntimeException e) {
      LOG.warn("Cannot build snippet for {}: {}", docId, e.toString());
      return "";
    }
    try {
      return readSnippet(file, pattern, StandardCharsets.ISO_8859_1);
    } catch (IOException | RuntimeException e) {
      LOG.warn("Cannot build snippet for {}: {}", docId, e.toString());
      return "";
    }
  }

  /** Matches any of the terms as a whole word, ignoring case ("cat" does not match "category"). */
  private static Pattern wholeWordPattern(Collection<String> terms) {
    StringBuilder alternatives = new StringBuilder();
    for (String term : terms) {
      if (alternatives.length() > 0) {
        alternatives.append('|');
      }
      alternatives.append(Pattern.quote(term));
    }
    return Pattern.compile(
        "(?<![\\p{L}\\p{N}])(?:" + alternatives + ")(?![\\p{L}\\p{N}])",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }

  private String readSnippet(Path file, Pattern pattern, Charset charset) throws IOException {
    StringBuilder text = new StringBuilder(); // normalized body text read so far
    int[] match = {-1, -1}; // start and end of the first match in text
    int[] enough = {0}; // stop reading when text is at least this long

    try (BufferedReader reader = Files.newBufferedReader(file, charset)) {
      tokenizer.forEachBodyLine(
          reader,
          line -> {
            String normalized = WHITESPACE.matcher(line).replaceAll(" ").strip();
            if (normalized.isEmpty()) {
              return true;
            }
            int from = text.length();
            if (from > 0) {
              text.append(' ');
            }
            text.append(normalized);

            if (match[0] < 0) {
              // Search only in the new part; transparent bounds let the look-behind see the old text.
              Matcher matcher = pattern.matcher(text);
              matcher.useTransparentBounds(true).useAnchoringBounds(false);
              matcher.region(from, text.length());
              if (matcher.find()) {
                match[0] = matcher.start();
                match[1] = matcher.end();
                enough[0] = match[1] + halfWindow(match[1] - match[0]) + 1;
              }
            }
            return match[0] < 0 || text.length() < enough[0]; // false = stop reading
          });
    }

    if (match[0] < 0) {
      return "";
    }
    return cut(text, match[0], match[1]);
  }

  private static int halfWindow(int matchLength) {
    return Math.max(0, (SNIPPET_LENGTH - matchLength) / 2);
  }

  /** Cuts about SNIPPET_LENGTH characters around the match without splitting words. */
  private static String cut(StringBuilder text, int matchStart, int matchEnd) {
    int half = halfWindow(matchEnd - matchStart);
    int start = Math.max(0, matchStart - half);
    int end = Math.min(text.length(), matchEnd + half);

    if (start > 0 && text.charAt(start - 1) != ' ') {
      int space = text.indexOf(" ", start);
      if (space >= 0 && space < matchStart) {
        start = space + 1;
      }
    }
    if (end < text.length() && text.charAt(end) != ' ') {
      int space = text.lastIndexOf(" ", end);
      if (space > matchEnd) {
        end = space;
      }
    }

    String body = text.substring(start, end).strip();
    return (start > 0 ? ELLIPSIS : "") + body + (end < text.length() ? ELLIPSIS : "");
  }
}
