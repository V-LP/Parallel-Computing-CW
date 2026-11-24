package ua.coursework.engine.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ThreadPoolTest {

  @Test
  void allSubmittedTasksAreExecuted() throws InterruptedException {
    ThreadPool pool = new ThreadPool(4);
    AtomicInteger counter = new AtomicInteger();

    for (int i = 0; i < 1_000; i++) {
      pool.submit(counter::incrementAndGet);
    }
    pool.shutdown();

    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    assertEquals(1_000, counter.get());
  }

  @Test
  void nTasksOnMThreadsGiveCorrectSum() throws InterruptedException {
    int tasks = 10_000;
    ThreadPool pool = new ThreadPool(8);
    AtomicLong sum = new AtomicLong();

    for (int i = 1; i <= tasks; i++) {
      long value = i;
      pool.submit(() -> sum.addAndGet(value));
    }
    pool.shutdown();

    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    assertEquals((long) tasks * (tasks + 1) / 2, sum.get());
  }

  @Test
  void shutdownWaitsForQueuedTasks() throws InterruptedException {
    ThreadPool pool = new ThreadPool(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch firstStarted = new CountDownLatch(1);
    AtomicInteger done = new AtomicInteger();

    // first task blocks the only worker, others queue
    pool.submit(
        () -> {
          firstStarted.countDown();
          awaitUninterruptibly(release);
          done.incrementAndGet();
        });
    assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
    for (int i = 0; i < 50; i++) {
      pool.submit(done::incrementAndGet);
    }

    pool.shutdown();
    assertEquals(50, pool.queueSize(), "shutdown must not drop queued tasks");
    assertFalse(pool.awaitTermination(100, TimeUnit.MILLISECONDS), "a task is still running");

    release.countDown();
    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    assertEquals(51, done.get());
  }

  @Test
  void submitAfterShutdownIsRejected() throws InterruptedException {
    ThreadPool pool = new ThreadPool(2);
    pool.shutdown();

    assertThrows(RejectedExecutionException.class, () -> pool.submit(() -> {}));
    assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
  }

  @Test
  void exceptionInTaskDoesNotStopPool() throws InterruptedException {
    ThreadPool pool = new ThreadPool(1);
    AtomicInteger done = new AtomicInteger();

    pool.submit(
        () -> {
          throw new IllegalStateException("expected test failure");
        });
    pool.submit(
        () -> {
          throw new Error("expected test failure (Error)");
        });
    pool.submit(done::incrementAndGet);
    pool.submit(done::incrementAndGet);
    pool.shutdown();

    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    assertEquals(2, done.get());
  }

  @Test
  void awaitTerminationReturnsTrueForIdlePoolAfterShutdown() throws InterruptedException {
    ThreadPool pool = new ThreadPool(3);
    pool.shutdown();

    assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    pool.shutdown();
    assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS));
  }

  @Test
  void awaitTerminationReturnsFalseWithoutShutdown() throws InterruptedException {
    ThreadPool pool = new ThreadPool(2);
    try {
      assertFalse(pool.awaitTermination(100, TimeUnit.MILLISECONDS));
    } finally {
      pool.shutdown();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void workersAreNamedWorkerN() throws Exception {
    int threads = 3;
    ThreadPool pool = new ThreadPool(threads);
    List<String> names = new ArrayList<>();
    // barrier forces one task per worker
    CyclicBarrier barrier = new CyclicBarrier(threads);

    for (int i = 0; i < threads; i++) {
      pool.submit(
          () -> {
            synchronized (names) {
              names.add(Thread.currentThread().getName());
            }
            try {
              barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          });
    }
    pool.shutdown();

    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    assertEquals(Set.of("worker-1", "worker-2", "worker-3"), new HashSet<>(names));
  }

  @Test
  void activeCountAndQueueSizeAreReported() throws InterruptedException {
    ThreadPool pool = new ThreadPool(2);
    CountDownLatch bothStarted = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);

    for (int i = 0; i < 2; i++) {
      pool.submit(
          () -> {
            bothStarted.countDown();
            awaitUninterruptibly(release);
          });
    }
    assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
    for (int i = 0; i < 5; i++) {
      pool.submit(() -> {});
    }

    assertEquals(2, pool.activeCount());
    assertEquals(5, pool.queueSize());

    release.countDown();
    pool.shutdown();
    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    assertEquals(0, pool.activeCount());
    assertEquals(0, pool.queueSize());
  }

  @Test
  void invalidArgumentsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> new ThreadPool(0));
    assertThrows(IllegalArgumentException.class, () -> new ThreadPool(-1));

    ThreadPool pool = new ThreadPool(1);
    try {
      assertThrows(NullPointerException.class, () -> pool.submit(null));
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void stressManyTinyTasksFromManyProducers() throws InterruptedException {
    int producers = 4;
    int perProducer = 25_000;
    ThreadPool pool = new ThreadPool(8);
    AtomicInteger counter = new AtomicInteger();

    Thread[] threads = new Thread[producers];
    for (int p = 0; p < producers; p++) {
      threads[p] =
          new Thread(
              () -> {
                for (int i = 0; i < perProducer; i++) {
                  pool.submit(counter::incrementAndGet);
                }
              });
      threads[p].start();
    }
    for (Thread t : threads) {
      t.join();
    }
    pool.shutdown();

    assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
    assertEquals(producers * perProducer, counter.get());
    assertEquals(0, pool.queueSize());
    assertEquals(0, pool.activeCount());
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
