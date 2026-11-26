package ua.coursework.engine.search;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.coursework.engine.index.BookEncoding;
import ua.coursework.engine.index.Chunker;
import ua.coursework.engine.index.Tokenizer;

final class SnippetExtractor {

  private static final Logger LOG = LoggerFactory.getLogger(SnippetExtractor.class);
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final String ELLIPSIS = "...";

  static final int SNIPPET_LENGTH = 200;

  private final Tokenizer tokenizer;
  private final Path dataDir;
  private final Chunker chunker;

  SnippetExtractor(Tokenizer tokenizer, Path dataDir, Chunker chunker) {
    this.tokenizer = tokenizer;
    this.dataDir = dataDir;
    this.chunker = chunker;
  }

  String extract(String docId, Collection<String> terms) {
    if (terms.isEmpty()) {
      return "";
    }
    Pattern pattern = wholeWordPattern(terms);
    Chunker.DocRef ref = Chunker.parseDocId(docId);
    Path file = dataDir.resolve(ref.path());
    try {
      // same charset rule as the indexer
      Charset charset = BookEncoding.detect(file);
      return readSnippet(file, charset, pattern, ref.chunk());
    } catch (IOException | RuntimeException e) {
      LOG.warn("Cannot build snippet for {}: {}", docId, e.toString());
      return "";
    }
  }

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

  private String readSnippet(Path file, Charset charset, Pattern pattern, int wantedChunk)
      throws IOException {
    StringBuilder text = new StringBuilder();
    int[] match = {-1, -1};
    int[] enough = {0};

    try (BufferedReader reader = Files.newBufferedReader(file, charset)) {
      chunker.forEachBodyLine(
          tokenizer,
          reader,
          (chunk, line) -> {
            if (wantedChunk > 0) {
              if (chunk < wantedChunk) {
                return true;
              }
              if (chunk > wantedChunk) {
                return false;
              }
            }
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
              // transparent bounds let look-behind see old text
              Matcher matcher = pattern.matcher(text);
              matcher.useTransparentBounds(true).useAnchoringBounds(false);
              matcher.region(from, text.length());
              if (matcher.find()) {
                match[0] = matcher.start();
                match[1] = matcher.end();
                enough[0] = match[1] + halfWindow(match[1] - match[0]) + 1;
              }
            }
            return match[0] < 0 || text.length() < enough[0];
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
