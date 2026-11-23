package ua.coursework.engine.index;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
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
 * <p>One book is one task of the {@link ThreadPool}. A worker reads its book line by line. With
 * chunking on (see {@link Chunker}) every chunk is one document: as soon as a chunk is complete the
 * worker counts its terms in a local map, writes them to the shared index (one {@code addPosting}
 * per (term, chunk) pair, document id {@code path#N}) and clears the map. So a task never holds more
 * than one chunk in memory, and workers share nothing but the index. With chunking off one book is
 * one document with the id {@code path}.
 */
public class Indexer {

  private static final Logger LOG = LoggerFactory.getLogger(Indexer.class);
  private static final int PROGRESS_EVERY = 1_000;

  private final ConcurrentInvertedIndex index;
  private final Tokenizer tokenizer;
  private final Chunker chunker;
  private volatile int booksIndexed;

  /** An indexer without chunking: one file is one document. */
  public Indexer(ConcurrentInvertedIndex index, Tokenizer tokenizer) {
    this(index, tokenizer, Chunker.disabled());
  }

  public Indexer(ConcurrentInvertedIndex index, Tokenizer tokenizer, Chunker chunker) {
    this.index = Objects.requireNonNull(index, "index");
    this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
    this.chunker = Objects.requireNonNull(chunker, "chunker");
  }

  /** Number of books (files) that gave at least one document in the last {@link #buildIndex}. */
  public int booksIndexed() {
    return booksIndexed;
  }

  /**
   * Indexes all {@code .txt} files under {@code dataDir} (subdirectories included) using {@code
   * threads} worker threads and returns the number of indexed documents (chunks). A file that
   * cannot be read is logged and skipped. A file or chunk without any terms is skipped too, because
   * it carries no information.
   *
   * @throws IOException if the directory itself cannot be read
   */
  public int buildIndex(Path dataDir, int threads) throws IOException {
    if (threads < 1) {
      throw new IllegalArgumentException("threads must be >= 1, got " + threads);
    }
    List<Path> files = listTextFiles(dataDir);
    LOG.info(
        "Indexing {} files from {} with {} threads, chunk size {}",
        files.size(),
        dataDir,
        threads,
        chunker.isEnabled() ? chunker.limitChars() / 1024 + " KB" : "off (file = document)");

    AtomicInteger documents = new AtomicInteger();
    AtomicInteger books = new AtomicInteger();
    AtomicInteger skipped = new AtomicInteger();
    AtomicInteger finished = new AtomicInteger();

    ThreadPool pool = new ThreadPool(threads);
    try {
      for (Path file : files) {
        pool.submit(
            () -> {
              int created = indexFile(dataDir, file);
              if (created > 0) {
                documents.addAndGet(created);
                books.incrementAndGet();
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

    booksIndexed = books.get();
    LOG.info(
        "Indexed {} books into {} documents (chunks), skipped {} files",
        books.get(),
        documents.get(),
        skipped.get());
    return documents.get();
  }

  /** Opens a file for reading. A separate method so that tests can make it fail. */
  BufferedReader openReader(Path file, Charset charset) throws IOException {
    return Files.newBufferedReader(file, charset); // throws on malformed input, does not replace
  }

  /** Indexes one book and returns the number of documents it gave. Never throws. */
  private int indexFile(Path dataDir, Path file) {
    String name = dataDir.relativize(file).toString().replace('\\', '/');
    BookState book = new BookState(name);
    try {
      // The charset is chosen for the whole file first: a book that turns out to be invalid UTF-8
      // in the middle could not be re-read, because its first chunks would already be indexed.
      Charset charset = BookEncoding.detect(file);
      try (BufferedReader reader = openReader(file, charset)) {
        chunker.forEachBodyLine(
            tokenizer,
            reader,
            (chunk, line) -> {
              book.accept(chunk, line);
              return true;
            });
      }
      book.finishChunk();
    } catch (IOException | RuntimeException e) {
      book.finishChunkQuietly();
      if (book.documents > 0) {
        LOG.warn("Cannot read all of {} ({} chunks were indexed): {}", name, book.documents, e);
      } else {
        LOG.warn("Cannot index {}: {}", name, e.toString());
      }
    }
    if (book.documents == 0) {
      LOG.debug("No terms in {}, skipped", name);
    }
    return book.documents;
  }

  /** The state of one book while a worker reads it: the term counts of the current chunk. */
  private final class BookState {
    private final String name;
    private final Map<String, int[]> counts = new HashMap<>(); // local to this task: no locking
    private int chunk = 1;
    private int length;
    private int documents;

    BookState(String name) {
      this.name = name;
    }

    void accept(int lineChunk, String line) {
      if (lineChunk != chunk) {
        finishChunk(); // the previous chunk is complete: it becomes a document now
        chunk = lineChunk;
      }
      tokenizer.tokenizeLine(
          line,
          term -> {
            length++;
            int[] counter = counts.get(term);
            if (counter == null) {
              counts.put(term, new int[] {1}); // int[1] is a cheap mutable counter
            } else {
              counter[0]++;
            }
          });
    }

    /** Writes the current chunk to the index (if it has terms) and clears the local counts. */
    void finishChunk() {
      if (length > 0) {
        int docId = index.registerDocument(chunker.docId(name, chunk));
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
          index.addPosting(entry.getKey(), docId, entry.getValue()[0]);
        }
        index.setDocumentLength(docId, length);
        documents++;
      }
      counts.clear();
      length = 0;
    }

    void finishChunkQuietly() {
      try {
        finishChunk();
      } catch (RuntimeException e) {
        LOG.warn("Cannot finish the last chunk of {}: {}", name, e.toString());
      }
    }
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
}
