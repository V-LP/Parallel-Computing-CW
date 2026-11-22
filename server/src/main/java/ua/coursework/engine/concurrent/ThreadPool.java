package ua.coursework.engine.concurrent;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Own fixed-size thread pool.
 *
 * <p>Tasks wait in a plain {@link ArrayDeque} that is guarded by the monitor {@code queueLock}.
 * Idle workers sleep in {@code queueLock.wait()} and are woken with {@code notify()} when a task
 * arrives. Only {@code Thread}, {@code synchronized}, {@code wait} and {@code notify} are used,
 * with no ready-made executor classes from {@code java.util.concurrent}.
 */
public final class ThreadPool {

  private static final Logger LOG = LoggerFactory.getLogger(ThreadPool.class);

  /** Monitor for everything below: the queue and the counters. Only workers wait on it. */
  private final Object queueLock = new Object();

  /** Separate monitor for {@link #awaitTermination}, so notify() on queueLock never hits it. */
  private final Object terminationLock = new Object();

  private final ArrayDeque<Runnable> queue = new ArrayDeque<>(); // guarded by queueLock
  private final Thread[] workers;
  private int activeWorkers; // workers that are running a task now; guarded by queueLock
  private int liveWorkers; // workers that have not exited yet; guarded by queueLock
  private boolean shutdown; // guarded by queueLock
  private boolean terminated; // guarded by terminationLock

  /** Creates the pool and starts {@code threads} workers named {@code worker-1..worker-N}. */
  public ThreadPool(int threads) {
    if (threads < 1) {
      throw new IllegalArgumentException("threads must be >= 1, got " + threads);
    }
    this.workers = new Thread[threads];
    this.liveWorkers = threads;
    for (int i = 0; i < threads; i++) {
      workers[i] = new Thread(this::workerLoop, "worker-" + (i + 1));
      workers[i].start();
    }
  }

  /**
   * Adds a task to the queue.
   *
   * @throws RejectedExecutionException if {@link #shutdown()} has already been called
   * @throws NullPointerException if the task is null
   */
  public void submit(Runnable task) {
    Objects.requireNonNull(task, "task");
    synchronized (queueLock) {
      if (shutdown) {
        throw new RejectedExecutionException("ThreadPool is shut down");
      }
      queue.addLast(task);
      // One new task needs one worker, so waking a single sleeping worker is enough.
      queueLock.notify();
    }
  }

  /**
   * Stops accepting new tasks. Tasks already in the queue or running are still completed. Does not
   * block; use {@link #awaitTermination} to wait. Calling it again has no effect.
   */
  public void shutdown() {
    synchronized (queueLock) {
      shutdown = true;
      // Wake every sleeping worker so that idle ones notice the flag and exit.
      queueLock.notifyAll();
    }
  }

  /**
   * Waits until all workers have exited (needs a prior {@link #shutdown()}).
   *
   * @return true if the pool terminated, false if the timeout elapsed first
   */
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    synchronized (terminationLock) {
      while (!terminated) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          return false;
        }
        TimeUnit.NANOSECONDS.timedWait(terminationLock, remaining);
      }
      return true;
    }
  }

  /** Number of workers that are executing a task right now. */
  public int activeCount() {
    synchronized (queueLock) {
      return activeWorkers;
    }
  }

  /** Number of tasks that are waiting in the queue (not started yet). */
  public int queueSize() {
    synchronized (queueLock) {
      return queue.size();
    }
  }

  private void workerLoop() {
    try {
      while (true) {
        Runnable task = takeTask();
        if (task == null) {
          return; // shut down and queue is empty
        }
        runTask(task);
      }
    } finally {
      onWorkerExit();
    }
  }

  /** Blocks until a task is available. Returns null when the pool is shut down and drained. */
  private Runnable takeTask() {
    synchronized (queueLock) {
      while (queue.isEmpty()) {
        if (shutdown) {
          return null;
        }
        try {
          queueLock.wait();
        } catch (InterruptedException e) {
          // The pool is stopped only via shutdown(); re-check the conditions and carry on.
        }
      }
      activeWorkers++;
      return queue.removeFirst();
    }
  }

  private void runTask(Runnable task) {
    try {
      task.run();
    } catch (Throwable t) {
      // A failing task must not kill the worker: log it and take the next task.
      LOG.error("Task failed in {}", Thread.currentThread().getName(), t);
    } finally {
      synchronized (queueLock) {
        activeWorkers--;
      }
    }
  }

  private void onWorkerExit() {
    boolean last;
    synchronized (queueLock) {
      liveWorkers--;
      last = liveWorkers == 0;
    }
    if (last) {
      synchronized (terminationLock) {
        terminated = true;
        terminationLock.notifyAll();
      }
    }
  }
}
