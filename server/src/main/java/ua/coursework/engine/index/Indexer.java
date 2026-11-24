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

public class Indexer {

  private static final Logger LOG = LoggerFactory.getLogger(Indexer.class);
  private static final int PROGRESS_EVERY = 1_000;

  private final ConcurrentInvertedIndex index;
  private final Tokenizer tokenizer;
  private final Chunker chunker;
  private volatile int booksIndexed;

  public Indexer(ConcurrentInvertedIndex index, Tokenizer tokenizer) {
    this(index, tokenizer, Chunker.disabled());
  }

  public Indexer(ConcurrentInvertedIndex index, Tokenizer tokenizer, Chunker chunker) {
    this.index = Objects.requireNonNull(index, "index");
    this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
    this.chunker = Objects.requireNonNull(chunker, "chunker");
  }

  public int booksIndexed() {
    return booksIndexed;
  }

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

  BufferedReader openReader(Path file, Charset charset) throws IOException {
    return Files.newBufferedReader(file, charset);
  }

  private int indexFile(Path dataDir, Path file) {
    String name = dataDir.relativize(file).toString().replace('\\', '/');
    BookState book = new BookState(name);
    try {
      // charset is chosen before reading chunks
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

  private final class BookState {
    private final String name;
    private final Map<String, int[]> counts = new HashMap<>(); // local to this task
    private int chunk = 1;
    private int length;
    private int documents;

    BookState(String name) {
      this.name = name;
    }

    void accept(int lineChunk, String line) {
      if (lineChunk != chunk) {
        finishChunk();
        chunk = lineChunk;
      }
      tokenizer.tokenizeLine(
          line,
          term -> {
            length++;
            int[] counter = counts.get(term);
            if (counter == null) {
              counts.put(term, new int[] {1});
            } else {
              counter[0]++;
            }
          });
    }

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
