package ua.coursework.engine.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ConcurrentInvertedIndexTest {

  private interface ThreadBody {
    void run(int threadId) throws Exception;
  }

  private static void runConcurrently(int threads, ThreadBody body) throws Exception {
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread[] all = new Thread[threads];
    for (int t = 0; t < threads; t++) {
      int id = t;
      all[t] =
          new Thread(
              () -> {
                ready.countDown();
                try {
                  start.await();
                  body.run(id);
                } catch (Throwable e) {
                  failure.compareAndSet(null, e);
                }
              },
              "test-thread-" + t);
      all[t].start();
    }
    assertTrue(ready.await(10, TimeUnit.SECONDS));
    start.countDown();
    for (Thread t : all) {
      t.join();
    }
    if (failure.get() != null) {
      throw new AssertionError("A test thread failed: " + failure.get(), failure.get());
    }
  }

  @Test
  void singleThreadedAddAndGet() {
    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex();
    index.addPosting("apple", 1, 3);
    index.addPosting("apple", 2, 1);
    index.addPosting("banana", 1, 5);

    assertEquals(List.of(new Posting(1, 3), new Posting(2, 1)), index.getPostings("apple"));
    assertEquals(List.of(new Posting(1, 5)), index.getPostings("banana"));
    assertEquals(List.of(), index.getPostings("cherry"));
    assertEquals(2, index.termCount());
    assertEquals(3L, index.postingCount());
    assertEquals(2, index.documentFrequency("apple"));
    assertEquals(0, index.documentFrequency("cherry"));
  }

  @Test
  void getPostingsReturnsImmutableSnapshot() {
    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex();
    index.addPosting("word", 1, 1);

    List<Posting> snapshot = index.getPostings("word");
    index.addPosting("word", 2, 1);

    assertEquals(1, snapshot.size(), "a later write must not change an earlier snapshot");
    assertEquals(2, index.getPostings("word").size());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.add(new Posting(9, 9)));
  }

  @Test
  void documentRegistry() {
    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex();
    assertEquals(0, index.documentCount());

    int docs = 5_000;
    for (int i = 0; i < docs; i++) {
      assertEquals(i, index.registerDocument("book-" + i + ".txt"));
    }
    index.setDocumentLength(42, 1234);

    assertEquals(docs, index.documentCount());
    assertEquals("book-0.txt", index.documentPath(0));
    assertEquals("book-4999.txt", index.documentPath(docs - 1));
    assertEquals(1234, index.documentLength(42));
    assertEquals(0, index.documentLength(43), "length is 0 until it is set");
  }

  @Test
  void invalidArgumentsAreRejected() {
    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex();
    index.registerDocument("a.txt");

    assertThrows(NullPointerException.class, () -> index.addPosting(null, 1, 1));
    assertThrows(NullPointerException.class, () -> index.getPostings(null));
    assertThrows(IllegalArgumentException.class, () -> index.addPosting("x", -1, 1));
    assertThrows(IllegalArgumentException.class, () -> index.addPosting("x", 1, 0));
    assertThrows(NullPointerException.class, () -> index.registerDocument(null));
    assertThrows(IllegalArgumentException.class, () -> index.documentPath(7));
    assertThrows(IllegalArgumentException.class, () -> index.documentPath(-1));
    assertThrows(IllegalArgumentException.class, () -> index.documentLength(7));
    assertThrows(IllegalArgumentException.class, () -> index.setDocumentLength(7, 1));
    assertThrows(IllegalArgumentException.class, () -> index.setDocumentLength(0, -1));
  }

  @Test
  void invalidSegmentCountsAreRejected() {
    for (int bad : new int[] {0, -4, 3, 100, 1 << 17}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new ConcurrentInvertedIndex(bad),
          "segmentCount " + bad);
    }
    assertEquals(64, new ConcurrentInvertedIndex().segmentCount());
  }

  @Test
  void tableGrowsAndKeepsAllTerms() {
    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex(1);
    int initialBuckets = index.bucketCount();
    int terms = 10_000;

    for (int i = 0; i < terms; i++) {
      index.addPosting("term-" + i, i, 1 + i % 5);
    }

    assertTrue(index.bucketCount() > initialBuckets, "the table must have been resized");
    assertTrue(index.bucketCount() * 3 / 4 >= terms, "load factor must stay at or below 0.75");
    assertEquals(terms, index.termCount());
    for (int i = 0; i < terms; i++) {
      assertEquals(List.of(new Posting(i, 1 + i % 5)), index.getPostings("term-" + i));
    }
  }

  @Test
  void termsWithEqualHashCodesStayApart() {
    // these strings share a hashcode
    String[] colliding = {"AaAa", "AaBB", "BBAa", "BBBB"};
    for (String s : colliding) {
      assertEquals("AaAa".hashCode(), s.hashCode());
    }

    for (int segments : new int[] {1, 64}) {
      ConcurrentInvertedIndex index = new ConcurrentInvertedIndex(segments);
      for (int i = 0; i < colliding.length; i++) {
        index.addPosting(colliding[i], i, i + 1);
        index.addPosting(colliding[i], i + 10, 1);
      }
      for (int i = 0; i < colliding.length; i++) {
        assertEquals(
            List.of(new Posting(i, i + 1), new Posting(i + 10, 1)), index.getPostings(colliding[i]));
      }
      assertEquals(colliding.length, index.termCount());
    }
  }

  @Test
  void worksWithDifferentSegmentCounts() throws Exception {
    for (int segments : new int[] {1, 2, 4, 16, 64, 256, 1024}) {
      ConcurrentInvertedIndex index = new ConcurrentInvertedIndex(segments);
      int threads = 4;
      int perThread = 5_000;

      runConcurrently(
          threads,
          t -> {
            for (int i = 0; i < perThread; i++) {
              index.addPosting("c" + t + "_" + i, t, 1 + i % 3);
            }
          });

      assertEquals(segments, index.segmentCount());
      assertEquals(threads * perThread, index.termCount(), "segments=" + segments);
      assertEquals((long) threads * perThread, index.postingCount(), "segments=" + segments);
      for (int t = 0; t < threads; t++) {
        for (int i = 0; i < perThread; i++) {
          assertEquals(
              List.of(new Posting(t, 1 + i % 3)),
              index.getPostings("c" + t + "_" + i),
              "segments=" + segments);
        }
      }
    }
  }

  private static int frequencyOf(int i) {
    return 1 + i % 7;
  }

  @Test
  void parallelInsertLosesNothing() throws Exception {
    int threads = 8;
    int perThread = 50_000;
    int distinctTerms = 5_000;
    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex();

    runConcurrently(
        threads,
        t -> {
          for (int i = 0; i < perThread; i++) {
            index.addPosting("term" + (i % distinctTerms), t, frequencyOf(i));
          }
        });

    assertEquals(distinctTerms, index.termCount());
    assertEquals((long) threads * perThread, index.postingCount());

    int occurrencesPerThread = perThread / distinctTerms;
    for (int k = 0; k < distinctTerms; k++) {
      long expectedFrequencySum = 0;
      for (int j = 0; j < occurrencesPerThread; j++) {
        expectedFrequencySum += (long) threads * frequencyOf(k + j * distinctTerms);
      }
      List<Posting> postings = index.getPostings("term" + k);
      long actualFrequencySum = 0;
      int[] perDoc = new int[threads];
      for (Posting p : postings) {
        actualFrequencySum += p.termFrequency();
        perDoc[p.docId()]++;
      }
      assertEquals(threads * occurrencesPerThread, postings.size(), "term" + k);
      assertEquals(expectedFrequencySum, actualFrequencySum, "term" + k);
      for (int count : perDoc) {
        assertEquals(occurrencesPerThread, count, "term" + k);
      }
    }
  }

  @Test
  void parallelDocumentRegistrationGivesUniqueDenseIds() throws Exception {
    int threads = 8;
    int perThread = 2_000;
    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex();
    int[][] ids = new int[threads][perThread];

    runConcurrently(
        threads,
        t -> {
          for (int i = 0; i < perThread; i++) {
            ids[t][i] = index.registerDocument("t" + t + "-doc" + i);
          }
        });

    int total = threads * perThread;
    assertEquals(total, index.documentCount());
    boolean[] seen = new boolean[total];
    for (int t = 0; t < threads; t++) {
      for (int i = 0; i < perThread; i++) {
        int id = ids[t][i];
        assertTrue(id >= 0 && id < total, "id out of range: " + id);
        assertFalse(seen[id], "id issued twice: " + id);
        seen[id] = true;
        assertEquals("t" + t + "-doc" + i, index.documentPath(id));
      }
    }
  }

  @Test
  void readersSeeConsistentDataWhileWritersWrite() throws Exception {
    int writers = 4;
    int readers = 4;
    int perWriter = 20_000;
    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex(8); // fewer segments, more contention
    AtomicInteger writersLeft = new AtomicInteger(writers);
    AtomicLong readerSnapshots = new AtomicLong();

    runConcurrently(
        writers + readers,
        id -> {
          if (id < writers) {
            for (int i = 0; i < perWriter; i++) {
              int docId = id * perWriter + i;
              index.addPosting("shared", docId, 1 + docId % 9);
              index.addPosting("own" + id + "_" + i % 100, docId, 1);
            }
            writersLeft.decrementAndGet();
          } else {
            int lastSize = 0;
            do {
              List<Posting> snapshot = index.getPostings("shared");
              if (snapshot.size() < lastSize) {
                throw new AssertionError("posting list shrank: " + lastSize + " -> " + snapshot.size());
              }
              lastSize = snapshot.size();
              for (Posting p : snapshot) {
                if (p.termFrequency() != 1 + p.docId() % 9) {
                  throw new AssertionError("corrupted posting " + p);
                }
              }
              if (index.documentFrequency("shared") < lastSize) {
                throw new AssertionError("documentFrequency is behind an earlier snapshot");
              }
              readerSnapshots.incrementAndGet();
            } while (writersLeft.get() > 0);
          }
        });

    assertTrue(readerSnapshots.get() >= readers, "every reader must have read at least once");
    List<Posting> shared = index.getPostings("shared");
    assertEquals(writers * perWriter, shared.size());
    boolean[] seen = new boolean[writers * perWriter];
    for (Posting p : shared) {
      assertFalse(seen[p.docId()], "duplicate docId " + p.docId());
      seen[p.docId()] = true;
    }
    assertEquals(1 + 100 * writers, index.termCount());
    assertEquals(2L * writers * perWriter, index.postingCount());
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void stressMixedReadsAndWrites() throws Exception {
    int threads = 16;
    int opsPerThread = 30_000;
    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex();
    AtomicLong adds = new AtomicLong();
    AtomicBoolean sawBrokenSnapshot = new AtomicBoolean();

    runConcurrently(
        threads,
        t -> {
          long state = 0x9E3779B97F4A7C15L * (t + 1);
          for (int i = 0; i < opsPerThread; i++) {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            String term = "w" + (int) ((state >>> 1) % 2_000);
            if (i % 4 == 0) {
              List<Posting> snapshot = index.getPostings(term);
              if (index.documentFrequency(term) < snapshot.size()) {
                sawBrokenSnapshot.set(true);
              }
            } else {
              index.addPosting(term, t, 1);
              adds.incrementAndGet();
            }
          }
        });

    assertFalse(sawBrokenSnapshot.get());
    assertEquals(adds.get(), index.postingCount());
    long total = 0;
    for (int k = 0; k < 2_000; k++) {
      total += index.getPostings("w" + k).size();
    }
    assertEquals(adds.get(), total);
  }
}
