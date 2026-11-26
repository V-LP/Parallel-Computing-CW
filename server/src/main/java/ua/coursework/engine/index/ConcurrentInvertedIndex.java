package ua.coursework.engine.index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class ConcurrentInvertedIndex {

  public static final int DEFAULT_SEGMENT_COUNT = 64;

  private static final int MAX_SEGMENT_COUNT = 1 << 16;
  private static final int INITIAL_BUCKETS = 16;
  private static final int INITIAL_DOC_CAPACITY = 1024;

  private final Segment[] segments;
  private final int segmentMask;
  private final int segmentBits;

  private final AtomicInteger termCount = new AtomicInteger();
  private final AtomicLong postingCount = new AtomicLong();

  // guarded by docLock
  private final ReentrantReadWriteLock docLock = new ReentrantReadWriteLock();
  private String[] docPaths = new String[INITIAL_DOC_CAPACITY];
  private int[] docLengths = new int[INITIAL_DOC_CAPACITY];
  private int docCount;

  public ConcurrentInvertedIndex() {
    this(DEFAULT_SEGMENT_COUNT);
  }

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

  public List<Posting> getPostings(String term) {
    Objects.requireNonNull(term, "term");
    int hash = hash(term);
    return segments[hash & segmentMask].get(term, hash);
  }

  public int documentFrequency(String term) {
    Objects.requireNonNull(term, "term");
    int hash = hash(term);
    return segments[hash & segmentMask].count(term, hash);
  }

  public int termCount() {
    return termCount.get();
  }

  public long postingCount() {
    return postingCount.get();
  }

  public int segmentCount() {
    return segments.length;
  }

  int bucketCount() {
    int total = 0;
    for (Segment segment : segments) {
      total += segment.bucketCount();
    }
    return total;
  }

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

  public String documentPath(int docId) {
    docLock.readLock().lock();
    try {
      checkDocId(docId);
      return docPaths[docId];
    } finally {
      docLock.readLock().unlock();
    }
  }

  public int documentLength(int docId) {
    docLock.readLock().lock();
    try {
      checkDocId(docId);
      return docLengths[docId];
    } finally {
      docLock.readLock().unlock();
    }
  }

  public int documentCount() {
    docLock.readLock().lock();
    try {
      return docCount;
    } finally {
      docLock.readLock().unlock();
    }
  }

  // call with docLock held
  private void checkDocId(int docId) {
    if (docId < 0 || docId >= docCount) {
      throw new IllegalArgumentException("Unknown docId: " + docId);
    }
  }

  // spread high bits into low bits
  private static int hash(String term) {
    int h = term.hashCode();
    return h ^ (h >>> 16);
  }

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

  private static final class Segment {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final int segmentBits;

    private Entry[] buckets = new Entry[INITIAL_BUCKETS];
    private int size;

    Segment(int segmentBits) {
      this.segmentBits = segmentBits;
    }

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

    // call with the lock held
    private Entry find(String term, int hash) {
      for (Entry e = buckets[bucketIndex(hash, buckets.length)]; e != null; e = e.next) {
        if (e.hash == hash && e.term.equals(term)) {
          return e;
        }
      }
      return null;
    }

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
