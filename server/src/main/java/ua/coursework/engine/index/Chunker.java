package ua.coursework.engine.index;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cuts the body of a book into chunks of about the same size. A chunk is one document of the index.
 *
 * <p>The rules (the indexer and the snippet builder both use this class, so they always agree on
 * where a chunk starts and ends):
 *
 * <ul>
 *   <li>A chunk is made of consecutive lines of the book body (after the Gutenberg header, before
 *       the footer). Chunks are numbered 1, 2, 3, ...
 *   <li>The size of a line is {@code length + 1} (the line break counts as one character).
 *   <li>A chunk is closed at the first line where its size reaches the limit. The line stays in the
 *       chunk, so a chunk is cut only between lines, never in the middle of a word.
 *   <li>A last piece smaller than {@code minTailChars} (1 KB by default) is not a chunk of its own:
 *       it is added to the previous chunk. A book always has at least chunk 1.
 * </ul>
 *
 * <p>The chunker holds only its settings, so one instance can be shared by all threads. The state of
 * one book lives in a {@link Splitter}.
 */
public final class Chunker {

  /** Receives the lines of a book together with the number of the chunk they belong to. */
  @FunctionalInterface
  public interface LineSink {
    /** Returns false to stop reading the book. */
    boolean line(int chunk, String text);
  }

  /** A document id split into the file path and the chunk number (0 = the whole file). */
  public record DocRef(String path, int chunk) {}

  public static final int DEFAULT_CHUNK_SIZE_KB = 100;
  private static final long KB = 1024;
  private static final Logger LOG = LoggerFactory.getLogger(Chunker.class);

  private final long limitChars; // 0 = chunking is off
  private final long minTailChars;

  /**
   * @param limitChars a chunk is closed when it has at least this many characters; 0 turns
   *     chunking off (the whole book is chunk 1)
   * @param minTailChars the last piece shorter than this is merged into the previous chunk; must
   *     not be larger than the limit
   */
  public Chunker(long limitChars, long minTailChars) {
    if (limitChars < 0 || minTailChars < 0) {
      throw new IllegalArgumentException("sizes must not be negative");
    }
    if (limitChars > 0 && minTailChars > limitChars) {
      throw new IllegalArgumentException("minTailChars must not be larger than limitChars");
    }
    this.limitChars = limitChars;
    this.minTailChars = minTailChars;
  }

  /** No chunks: one file is one document, as before. */
  public static Chunker disabled() {
    return new Chunker(0, 0);
  }

  /** Chunks of about {@code kb} kilobytes (characters); 0 turns chunking off. */
  public static Chunker ofKb(int kb) {
    if (kb < 0) {
      throw new IllegalArgumentException("chunk size must not be negative: " + kb);
    }
    return kb == 0 ? disabled() : new Chunker(kb * KB, KB);
  }

  /** Reads CHUNK_SIZE_KB (default 100, 0 = off). A bad value is logged and replaced by 100. */
  public static Chunker fromEnv() {
    String value = System.getenv("CHUNK_SIZE_KB");
    int kb = DEFAULT_CHUNK_SIZE_KB;
    if (value != null && !value.isBlank()) {
      try {
        int parsed = Integer.parseInt(value.trim());
        if (parsed >= 0) {
          kb = parsed;
        } else {
          LOG.warn("CHUNK_SIZE_KB must be >= 0, using {}", kb);
        }
      } catch (NumberFormatException e) {
        LOG.warn("Invalid CHUNK_SIZE_KB '{}', using {}", value, kb);
      }
    }
    return ofKb(kb);
  }

  public boolean isEnabled() {
    return limitChars > 0;
  }

  public long limitChars() {
    return limitChars;
  }

  /** The id of a chunk document: {@code path#N}; without chunking just the path. */
  public String docId(String path, int chunk) {
    return isEnabled() ? path + "#" + chunk : path;
  }

  /** Splits {@code path#N} into path and N. An id without a valid {@code #N} gives chunk 0. */
  public static DocRef parseDocId(String docId) {
    int hash = docId.lastIndexOf('#');
    if (hash > 0 && hash < docId.length() - 1) {
      String digits = docId.substring(hash + 1);
      if (digits.chars().allMatch(c -> c >= '0' && c <= '9') && digits.length() <= 9) {
        int chunk = Integer.parseInt(digits);
        if (chunk >= 1) {
          return new DocRef(docId.substring(0, hash), chunk);
        }
      }
    }
    return new DocRef(docId, 0);
  }

  /** Starts splitting one book. */
  public Splitter newSplitter(LineSink sink) {
    return new Splitter(sink);
  }

  /**
   * Reads the body of a book with the tokenizer (header and footer skipped) and passes every line
   * with its chunk number to {@code sink}. Stops when the sink returns false.
   */
  public void forEachBodyLine(Tokenizer tokenizer, BufferedReader reader, LineSink sink)
      throws IOException {
    Splitter splitter = newSplitter(sink);
    tokenizer.forEachBodyLine(reader, splitter::add);
    splitter.finish();
  }

  /** The chunking state of one book. Not shared between threads. */
  public final class Splitter {
    private final LineSink sink;
    private int chunk = 1;
    private long size; // characters in the current chunk
    private boolean closed; // the current chunk is closed, lines now wait in tail
    private final List<String> tail = new ArrayList<>();
    private long tailSize;
    private boolean stopped;

    private Splitter(LineSink sink) {
      this.sink = sink;
    }

    /** Adds the next line of the body. Returns false when the sink wants no more lines. */
    public boolean add(String line) {
      if (stopped) {
        return false;
      }
      if (!isEnabled()) {
        return deliver(line);
      }
      if (!closed) {
        if (!deliver(line)) {
          return false;
        }
        size += line.length() + 1;
        if (size >= limitChars) {
          closed = true;
          tailSize = 0;
        }
        return true;
      }

      // The chunk is closed. Keep the next lines until we know if they are long enough to be a
      // new chunk (then chunk+1 starts at the first of them) or only a short end of the book.
      tail.add(line);
      tailSize += line.length() + 1;
      if (tailSize >= minTailChars) {
        chunk++;
        closed = false;
        size = 0;
        List<String> lines = new ArrayList<>(tail);
        tail.clear();
        for (String kept : lines) {
          if (!deliver(kept)) {
            return false;
          }
          size += kept.length() + 1;
        }
        if (size >= limitChars) {
          closed = true;
          tailSize = 0;
        }
      }
      return true;
    }

    /** Call at the end of the body: a short last piece is added to the previous chunk. */
    public void finish() {
      if (stopped) {
        return;
      }
      for (String kept : tail) {
        if (!deliver(kept)) {
          break;
        }
      }
      tail.clear();
    }

    private boolean deliver(String line) {
      if (!sink.line(chunk, line)) {
        stopped = true;
        return false;
      }
      return true;
    }
  }
}
