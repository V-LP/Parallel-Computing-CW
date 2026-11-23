package ua.coursework.engine.index;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the current index. A new index is built on the side and then published with one write to a
 * volatile field, so readers see either the old complete index or the new complete one, never a
 * half-built index. Only one build may run at a time.
 */
public class IndexManager {

  private static final Logger LOG = LoggerFactory.getLogger(IndexManager.class);
  private static final long MB = 1024 * 1024;

  private final Tokenizer tokenizer;
  private final Chunker chunker;
  private final AtomicBoolean indexing = new AtomicBoolean();
  private volatile IndexSnapshot current =
      new IndexSnapshot(new ConcurrentInvertedIndex(), 0, 0, 0);

  /** A manager without chunking: one file is one document. */
  public IndexManager(Tokenizer tokenizer) {
    this(tokenizer, Chunker.disabled());
  }

  public IndexManager(Tokenizer tokenizer, Chunker chunker) {
    this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
    this.chunker = Objects.requireNonNull(chunker, "chunker");
  }

  /** The chunker used for every build. Snippets must use the same one to find the same chunks. */
  public Chunker chunker() {
    return chunker;
  }

  /** The latest finished index (an empty one before the first build). Never null. */
  public IndexSnapshot current() {
    return current;
  }

  public boolean isIndexing() {
    return indexing.get();
  }

  /**
   * Builds a new index from {@code dataDir} and replaces the current one with it.
   *
   * @throws IndexingInProgressException if another build is running (nothing is changed)
   * @throws IOException if the data directory cannot be read (the old index stays)
   */
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
      current = snapshot; // the atomic switch
      logSummary(snapshot, indexer.booksIndexed());
      return snapshot;
    } finally {
      indexing.set(false);
    }
  }

  /** Creates the indexer for one build. A separate method so that tests can replace it. */
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
