package ua.coursework.engine.index;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.coursework.engine.concurrent.ThreadPool;

/**
 * Builds the index from a directory of {@code .txt} files in parallel.
 *
 * <p>One file is one document and one task of the {@link ThreadPool}. A worker reads its file line
 * by line, counts the terms in a local map (no sharing with other workers), and only then writes
 * the result into the shared index: one {@code addPosting} per (term, document) pair.
 */
public class Indexer {

  private static final Logger LOG = LoggerFactory.getLogger(Indexer.class);
  private static final int PROGRESS_EVERY = 1_000;

  private final ConcurrentInvertedIndex index;
  private final Tokenizer tokenizer;

  public Indexer(ConcurrentInvertedIndex index, Tokenizer tokenizer) {
    this.index = Objects.requireNonNull(index, "index");
    this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
  }

  /**
   * Indexes all {@code .txt} files under {@code dataDir} (subdirectories included) using {@code
   * threads} worker threads and returns the number of indexed documents. A file that cannot be read
   * is logged and skipped. A file without any terms is skipped too, because it carries no
   * information.
   *
   * @throws IOException if the directory itself cannot be read
   */
  public int buildIndex(Path dataDir, int threads) throws IOException {
    if (threads < 1) {
      throw new IllegalArgumentException("threads must be >= 1, got " + threads);
    }
    List<Path> files = listTextFiles(dataDir);
    LOG.info("Indexing {} files from {} with {} threads", files.size(), dataDir, threads);

    AtomicInteger indexed = new AtomicInteger();
    AtomicInteger skipped = new AtomicInteger();
    AtomicInteger finished = new AtomicInteger();

    ThreadPool pool = new ThreadPool(threads);
    try {
      for (Path file : files) {
        pool.submit(
            () -> {
              if (indexFile(dataDir, file)) {
                indexed.incrementAndGet();
              } else {
                skipped.incrementAndGet();
              }
              int done = finished.incrementAndGet();
              if (done % PROGRESS_EVERY == 0) {
                LOG.info("Progress: {}/{} files", done, files.size());
              }
            });
      }
    } finally {
      pool.shutdown();
    }
    awaitFinished(pool);

    LOG.info("Indexed {} documents, skipped {} files", indexed.get(), skipped.get());
    return indexed.get();
  }

  /** Opens a file for reading. A separate method so that tests can make it fail. */
  BufferedReader openReader(Path file, Charset charset) throws IOException {
    return Files.newBufferedReader(file, charset); // throws on malformed input, does not replace
  }

  /** Returns true if the file became a document. Never throws. */
  private boolean indexFile(Path dataDir, Path file) {
    String name = dataDir.relativize(file).toString().replace('\\', '/');
    try {
      TermCounts terms = readTerms(file);
      if (terms.length() == 0) {
        LOG.debug("No terms in {}, skipped", name);
        return false;
      }
      int docId = index.registerDocument(name);
      for (Map.Entry<String, int[]> entry : terms.counts().entrySet()) {
        index.addPosting(entry.getKey(), docId, entry.getValue()[0]);
      }
      index.setDocumentLength(docId, terms.length());
      return true;
    } catch (IOException | RuntimeException e) {
      LOG.warn("Cannot index {}: {}", name, e.toString());
      return false;
    }
  }

  /** Reads as UTF-8; if the bytes are not valid UTF-8, reads the file again as ISO-8859-1. */
  private TermCounts readTerms(Path file) throws IOException {
    try {
      return readTerms(file, StandardCharsets.UTF_8);
    } catch (MalformedInputException e) {
      LOG.debug("{} is not UTF-8, falling back to ISO-8859-1", file);
      return readTerms(file, StandardCharsets.ISO_8859_1);
    }
  }

  private TermCounts readTerms(Path file, Charset charset) throws IOException {
    Map<String, int[]> counts = new HashMap<>(); // local to this task, so no locking is needed
    int[] length = {0};
    try (BufferedReader reader = openReader(file, charset)) {
      tokenizer.tokenize(
          reader,
          term -> {
            length[0]++;
            int[] counter = counts.get(term);
            if (counter == null) {
              counts.put(term, new int[] {1}); // int[1] is a cheap mutable counter
            } else {
              counter[0]++;
            }
          });
    }
    return new TermCounts(counts, length[0]);
  }

  private static List<Path> listTextFiles(Path dataDir) throws IOException {
    try (Stream<Path> paths = Files.walk(dataDir)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
          .sorted()
          .toList();
    }
  }

  private static void awaitFinished(ThreadPool pool) {
    try {
      while (!pool.awaitTermination(1, TimeUnit.DAYS)) {
        LOG.warn("Indexing is still running after a day");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for indexing", e);
    }
  }

  /** Term counts of one document and its length in terms. */
  private record TermCounts(Map<String, int[]> counts, int length) {}
}
