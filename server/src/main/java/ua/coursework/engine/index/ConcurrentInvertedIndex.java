package ua.coursework.engine.index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Own thread-safe inverted index: term to list of {@link Posting}s, plus a registry of documents.
 *
 * <p>Design: the term space is split into a power-of-two number of <b>segments</b> (64 by default).
 * A term always belongs to one segment, chosen by its hash. Each segment is a small hash table that
 * we wrote ourselves (array of buckets, each bucket a linked list of entries, doubled when it gets
 * 75% full) guarded by its own {@link ReentrantReadWriteLock}. Writers to different segments do
 * not wait for each other, and many readers can read one segment at the same time. No ready-made
 * thread-safe map or list classes are used.
 *
 * <p>Documents have numeric ids that are issued by {@link #registerDocument(String)}. The mapping
 * id to path and the document lengths are kept in a separate registry with its own lock.
 */
public final class ConcurrentInvertedIndex {

  /** Default number of segments. */
  public static final int DEFAULT_SEGMENT_COUNT = 64;

  private static final int MAX_SEGMENT_COUNT = 1 << 16;
  private static final int INITIAL_BUCKETS = 16;
  private static final int INITIAL_DOC_CAPACITY = 1024;

  private final Segment[] segments;
  private final int segmentMask;
  private final int segmentBits;

  private final AtomicInteger termCount = new AtomicInteger();
  private final AtomicLong postingCount = new AtomicLong();

  // Document registry. All fields below are guarded by docLock.
  private final ReentrantReadWriteLock docLock = new ReentrantReadWriteLock();
  private String[] docPaths = new String[INITIAL_DOC_CAPACITY];
  private int[] docLengths = new int[INITIAL_DOC_CAPACITY];
  private int docCount;

  public ConcurrentInvertedIndex() {
    this(DEFAULT_SEGMENT_COUNT);
  }

  /**
   * @param segmentCount number of segments; must be a power of two between 1 and 65536
   */
  public ConcurrentInvertedIndex(int segmentCount) {
    if (segmentCount < 1
        || segmentCount > MAX_SEGMENT_COUNT
        || Integer.bitCount(segmentCount) != 1) {
      throw new IllegalArgumentException(
          "segmentCount must be a power of two in [1, " + MAX_SEGMENT_COUNT + "], got "
              + segmentCount);
    }
    this.segmentMask = segmentCount - 1;
    this.segmentBits = Integer.numberOfTrailingZeros(segmentCount);
    this.segments = new Segment[segmentCount];
    for (int i = 0; i < segmentCount; i++) {
      segments[i] = new Segment(segmentBits);
    }
  }

  // ---------------------------------------------------------------- terms and postings

  /**
   * Adds one posting to the list of {@code term}. The caller should add each (term, document) pair
   * once; equal pairs are not merged. Safe to call from many threads.
   *
   * @throws NullPointerException if term is null
   * @throws IllegalArgumentException if docId is negative or frequency is less than 1
   */
  public void addPosting(String term, int docId, int frequency) {
    Objects.requireNonNull(term, "term");
    if (docId < 0) {
      throw new IllegalArgumentException("docId must be >= 0, got " + docId);
    }
    if (frequency < 1) {
      throw new IllegalArgumentException("frequency must be >= 1, got " + frequency);
    }
    int hash = hash(term);
    boolean newTerm = segments[hash & segmentMask].add(term, hash, new Posting(docId, frequency));
    if (newTerm) {
      termCount.incrementAndGet();
    }
    postingCount.incrementAndGet();
  }

  /**
   * Returns a read-only copy of the posting list of {@code term} (empty if the term is unknown). The
   * copy is a snapshot: later writes do not change it, so the caller can use it without any lock.
   */
  public List<Posting> getPostings(String term) {
    Objects.requireNonNull(term, "term");
    int hash = hash(term);
    return segments[hash & segmentMask].get(term, hash);
  }

  /** Number of documents that contain {@code term} (the length of its posting list), for IDF. */
  public int documentFrequency(String term) {
    Objects.requireNonNull(term, "term");
    int hash = hash(term);
    return segments[hash & segmentMask].count(term, hash);
  }

  /** Number of distinct terms. */
  public int termCount() {
    return termCount.get();
  }

  /** Total number of postings in all lists. */
  public long postingCount() {
    return postingCount.get();
  }

  /** Number of segments (fixed when the index is created). */
  public int segmentCount() {
    return segments.length;
  }

  /** Total number of buckets over all segments. Package-private: used by tests to see resizing. */
  int bucketCount() {
    int total = 0;
    for (Segment segment : segments) {
      total += segment.bucketCount();
    }
    return total;
  }

  // ---------------------------------------------------------------- documents

  /**
   * Registers a document and returns its numeric id (0, 1, 2, ... in order of registration). Safe to
   * call from many threads: every caller gets a different id.
   */
  public int registerDocument(String path) {
    Objects.requireNonNull(path, "path");
    docLock.writeLock().lock();
    try {
      if (docCount == docPaths.length) {
        docPaths = Arrays.copyOf(docPaths, docCount * 2);
        docLengths = Arrays.copyOf(docLengths, docCount * 2);
      }
      docPaths[docCount] = path;
      return docCount++;
    } finally {
      docLock.writeLock().unlock();
    }
  }

  /** Stores the length of a document in tokens (needed for TF-IDF normalisation). */
  public void setDocumentLength(int docId, int length) {
    if (length < 0) {
      throw new IllegalArgumentException("length must be >= 0, got " + length);
    }
    docLock.writeLock().lock();
    try {
      checkDocId(docId);
      docLengths[docId] = length;
    } finally {
      docLock.writeLock().unlock();
    }
  }

  /** Path (or name) given to {@link #registerDocument(String)}. */
  public String documentPath(int docId) {
    docLock.readLock().lock();
    try {
      checkDocId(docId);
      return docPaths[docId];
    } finally {
      docLock.readLock().unlock();
    }
  }

  /** Length in tokens set by {@link #setDocumentLength(int, int)}; 0 if it was never set. */
  public int documentLength(int docId) {
    docLock.readLock().lock();
    try {
      checkDocId(docId);
      return docLengths[docId];
    } finally {
      docLock.readLock().unlock();
    }
  }

  /** Number of registered documents (N in the IDF formula). */
  public int documentCount() {
    docLock.readLock().lock();
    try {
      return docCount;
    } finally {
      docLock.readLock().unlock();
    }
  }

  /** Must be called while holding docLock. */
  private void checkDocId(int docId) {
    if (docId < 0 || docId >= docCount) {
      throw new IllegalArgumentException("Unknown docId: " + docId);
    }
  }

  // ---------------------------------------------------------------- hashing

  /** Mixes the high bits of hashCode into the low bits, so similar strings spread out. */
  private static int hash(String term) {
    int h = term.hashCode();
    return h ^ (h >>> 16);
  }

  // ---------------------------------------------------------------- segment

  /** One node in a bucket's linked list: a term and its posting list. */
  private static final class Entry {
    final String term;
    final int hash;
    final ArrayList<Posting> postings = new ArrayList<>(2);
    Entry next;

    Entry(String term, int hash, Entry next) {
      this.term = term;
      this.hash = hash;
      this.next = next;
    }
  }

  /** A hash table (array of buckets with linked lists) protected by one read-write lock. */
  private static final class Segment {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** Low {@code segmentBits} bits of the hash chose this segment, so buckets use the bits above. */
    private final int segmentBits;

    private Entry[] buckets = new Entry[INITIAL_BUCKETS]; // guarded by lock
    private int size; // number of terms in this segment; guarded by lock

    Segment(int segmentBits) {
      this.segmentBits = segmentBits;
    }

    /** Returns true if the term was new. */
    boolean add(String term, int hash, Posting posting) {
      lock.writeLock().lock();
      try {
        int index = bucketIndex(hash, buckets.length);
        for (Entry e = buckets[index]; e != null; e = e.next) {
          if (e.hash == hash && e.term.equals(term)) {
            e.postings.add(posting);
            return false;
          }
        }
        Entry entry = new Entry(term, hash, buckets[index]);
        entry.postings.add(posting);
        buckets[index] = entry;
        size++;
        if (size > buckets.length * 3 / 4) {
          resize();
        }
        return true;
      } finally {
        lock.writeLock().unlock();
      }
    }

    List<Posting> get(String term, int hash) {
      lock.readLock().lock();
      try {
        Entry e = find(term, hash);
        return e == null ? List.of() : List.copyOf(e.postings);
      } finally {
        lock.readLock().unlock();
      }
    }

    int count(String term, int hash) {
      lock.readLock().lock();
      try {
        Entry e = find(term, hash);
        return e == null ? 0 : e.postings.size();
      } finally {
        lock.readLock().unlock();
      }
    }

    int bucketCount() {
      lock.readLock().lock();
      try {
        return buckets.length;
      } finally {
        lock.readLock().unlock();
      }
    }

    /** Must be called while holding the lock. */
    private Entry find(String term, int hash) {
      for (Entry e = buckets[bucketIndex(hash, buckets.length)]; e != null; e = e.next) {
        if (e.hash == hash && e.term.equals(term)) {
          return e;
        }
      }
      return null;
    }

    /** Doubles the bucket array and moves every entry. Must be called under the write lock. */
    private void resize() {
      Entry[] old = buckets;
      Entry[] bigger = new Entry[old.length * 2];
      for (Entry head : old) {
        Entry e = head;
        while (e != null) {
          Entry next = e.next;
          int index = bucketIndex(e.hash, bigger.length);
          e.next = bigger[index];
          bigger[index] = e;
          e = next;
        }
      }
      buckets = bigger;
    }

    private int bucketIndex(int hash, int length) {
      return (hash >>> segmentBits) & (length - 1);
    }
  }
}
