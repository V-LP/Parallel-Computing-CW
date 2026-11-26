package ua.coursework.engine.index;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Chunker {

  @FunctionalInterface
  public interface LineSink {
    boolean line(int chunk, String text);
  }

  public record DocRef(String path, int chunk) {}

  public static final int DEFAULT_CHUNK_SIZE_KB = 100;
  private static final long KB = 1024;
  private static final Logger LOG = LoggerFactory.getLogger(Chunker.class);

  private final long limitChars;
  private final long minTailChars;

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

  public static Chunker disabled() {
    return new Chunker(0, 0);
  }

  public static Chunker ofKb(int kb) {
    if (kb < 0) {
      throw new IllegalArgumentException("chunk size must not be negative: " + kb);
    }
    return kb == 0 ? disabled() : new Chunker(kb * KB, KB);
  }

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

  public String docId(String path, int chunk) {
    return isEnabled() ? path + "#" + chunk : path;
  }

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

  public Splitter newSplitter(LineSink sink) {
    return new Splitter(sink);
  }

  public void forEachBodyLine(Tokenizer tokenizer, BufferedReader reader, LineSink sink)
      throws IOException {
    Splitter splitter = newSplitter(sink);
    tokenizer.forEachBodyLine(reader, splitter::add);
    splitter.finish();
  }

  public final class Splitter {
    private final LineSink sink;
    private int chunk = 1;
    private long size;
    private boolean closed;
    private final List<String> tail = new ArrayList<>();
    private long tailSize;
    private boolean stopped;

    private Splitter(LineSink sink) {
      this.sink = sink;
    }

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

      // tail lines wait until we know their size
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
