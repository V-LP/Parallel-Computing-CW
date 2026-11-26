package ua.coursework.engine.index;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IndexManager {

  private static final Logger LOG = LoggerFactory.getLogger(IndexManager.class);
  private static final long MB = 1024 * 1024;

  private final Tokenizer tokenizer;
  private final Chunker chunker;
  private final AtomicBoolean indexing = new AtomicBoolean();
  private volatile IndexSnapshot current =
      new IndexSnapshot(new ConcurrentInvertedIndex(), 0, 0, 0);

  public IndexManager(Tokenizer tokenizer) {
    this(tokenizer, Chunker.disabled());
  }

  public IndexManager(Tokenizer tokenizer, Chunker chunker) {
    this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
    this.chunker = Objects.requireNonNull(chunker, "chunker");
  }

  public Chunker chunker() {
    return chunker;
  }

  public IndexSnapshot current() {
    return current;
  }

  public boolean isIndexing() {
    return indexing.get();
  }

  public IndexSnapshot rebuild(Path dataDir, int threads) throws IOException {
    if (!indexing.compareAndSet(false, true)) {
      throw new IndexingInProgressException();
    }
    try {
      ConcurrentInvertedIndex fresh = new ConcurrentInvertedIndex();
      long start = System.nanoTime();
      Indexer indexer = newIndexer(fresh, tokenizer);
      int documents = indexer.buildIndex(dataDir, threads);
      long timeMs = (System.nanoTime() - start) / 1_000_000;

      IndexSnapshot snapshot = new IndexSnapshot(fresh, documents, timeMs, threads);
      current = snapshot; // atomic switch
      logSummary(snapshot, indexer.booksIndexed());
      return snapshot;
    } finally {
      indexing.set(false);
    }
  }

  Indexer newIndexer(ConcurrentInvertedIndex target, Tokenizer tokenizer) {
    return new Indexer(target, tokenizer, chunker);
  }

  private static void logSummary(IndexSnapshot snapshot, int books) {
    Runtime rt = Runtime.getRuntime();
    long usedMb = (rt.totalMemory() - rt.freeMemory()) / MB;
    LOG.info(
        "Index ready: {} documents (chunks) from {} books, {} terms, {} postings, {} ms, {} threads. "
            + "Heap used (approx., may include garbage): {} MB of max {} MB",
        snapshot.documents(),
        books,
        snapshot.index().termCount(),
        snapshot.index().postingCount(),
        snapshot.buildTimeMs(),
        snapshot.threadsUsed(),
        usedMb,
        rt.maxMemory() / MB);
  }
}
