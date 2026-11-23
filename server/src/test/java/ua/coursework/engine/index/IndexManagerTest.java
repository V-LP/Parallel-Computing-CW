package ua.coursework.engine.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class IndexManagerTest {

  @TempDir Path root;

  private Path dirWithFiles(String name, String... fileNames) throws IOException {
    Path dir = Files.createDirectories(root.resolve(name));
    for (String fileName : fileNames) {
      Files.writeString(dir.resolve(fileName), "some words in " + fileName.replace('.', ' '));
    }
    return dir;
  }

  @Test
  void startsWithAnEmptyIndex() {
    IndexManager manager = new IndexManager(new Tokenizer());

    IndexSnapshot snapshot = manager.current();

    assertEquals(0, snapshot.documents());
    assertEquals(0, snapshot.buildTimeMs());
    assertEquals(0, snapshot.threadsUsed());
    assertEquals(0, snapshot.index().documentCount());
    assertFalse(manager.isIndexing());
  }

  @Test
  void rebuildReplacesTheIndexAndLeavesTheOldSnapshotIntact() throws IOException {
    Path dirA = dirWithFiles("a", "one.txt", "two.txt");
    Path dirB = dirWithFiles("b", "three.txt");
    IndexManager manager = new IndexManager(new Tokenizer());

    IndexSnapshot first = manager.rebuild(dirA, 2);
    assertSame(first, manager.current());
    assertEquals(2, first.documents());
    assertEquals(2, first.threadsUsed());
    assertEquals(2, first.index().documentCount());

    IndexSnapshot second = manager.rebuild(dirB, 1);
    assertSame(second, manager.current());
    assertNotSame(first.index(), second.index());
    assertEquals(1, second.documents());
    assertEquals(1, second.threadsUsed());
    assertEquals("three.txt", second.index().documentPath(0));

    // Whoever still holds the first snapshot sees the old index, unchanged.
    assertEquals(2, first.index().documentCount());
    assertEquals(2, first.documents());
  }

  @Test
  void failedRebuildKeepsTheOldIndexAndReleasesTheLock() throws IOException {
    IndexManager manager = new IndexManager(new Tokenizer());
    IndexSnapshot good = manager.rebuild(dirWithFiles("a", "one.txt"), 1);

    assertThrows(IOException.class, () -> manager.rebuild(root.resolve("missing"), 1));

    assertSame(good, manager.current());
    assertFalse(manager.isIndexing());
    assertEquals(1, manager.rebuild(dirWithFiles("b", "x.txt"), 1).documents());
  }

  @Test
  void secondRebuildWhileOneIsRunningIsRejected() throws Exception {
    Path dir = dirWithFiles("a", "one.txt");
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    IndexManager manager =
        new IndexManager(new Tokenizer()) {
          @Override
          Indexer newIndexer(ConcurrentInvertedIndex target, Tokenizer tokenizer) {
            return new Indexer(target, tokenizer) {
              @Override
              BufferedReader openReader(Path file, Charset charset) throws IOException {
                started.countDown();
                try {
                  release.await(); // keep the first build busy until the test lets it go
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                return super.openReader(file, charset);
              }
            };
          }
        };

    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread first =
        new Thread(
            () -> {
              try {
                manager.rebuild(dir, 1);
              } catch (Throwable t) {
                failure.set(t);
              }
            });
    first.start();
    assertTrue(started.await(10, TimeUnit.SECONDS));

    assertTrue(manager.isIndexing());
    assertThrows(IndexingInProgressException.class, () -> manager.rebuild(dir, 1));
    assertEquals(0, manager.current().documents(), "half-built index must not be visible");

    release.countDown();
    first.join();
    assertEquals(null, failure.get());
    assertFalse(manager.isIndexing());
    assertEquals(1, manager.current().documents());
    assertEquals(1, manager.rebuild(dir, 1).documents(), "a new build is allowed afterwards");
  }
}
