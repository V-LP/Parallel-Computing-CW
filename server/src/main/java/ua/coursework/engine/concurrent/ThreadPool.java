package ua.coursework.engine.concurrent;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ThreadPool {

  private static final Logger LOG = LoggerFactory.getLogger(ThreadPool.class);

  // guards queue and counters
  private final Object queueLock = new Object();

  // separate monitor for awaitTermination
  private final Object terminationLock = new Object();

  private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
  private final Thread[] workers;
  private int activeWorkers;
  private int liveWorkers;
  private boolean shutdown;
  private boolean terminated;

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

  public void submit(Runnable task) {
    Objects.requireNonNull(task, "task");
    synchronized (queueLock) {
      if (shutdown) {
        throw new RejectedExecutionException("ThreadPool is shut down");
      }
      queue.addLast(task);
      queueLock.notify();
    }
  }

  public void shutdown() {
    synchronized (queueLock) {
      shutdown = true;
      // wake all so idle workers exit
      queueLock.notifyAll();
    }
  }

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

  public int activeCount() {
    synchronized (queueLock) {
      return activeWorkers;
    }
  }

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
          return;
        }
        runTask(task);
      }
    } finally {
      onWorkerExit();
    }
  }

  private Runnable takeTask() {
    synchronized (queueLock) {
      while (queue.isEmpty()) {
        if (shutdown) {
          return null;
        }
        try {
          queueLock.wait();
        } catch (InterruptedException e) {
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
      // a failing task must not kill the worker
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
