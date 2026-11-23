package ua.coursework.engine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import ua.coursework.engine.concurrent.ThreadPool;
import ua.coursework.engine.index.IndexManager;
import ua.coursework.engine.index.Tokenizer;
import ua.coursework.engine.llm.QueryExpander;
import ua.coursework.engine.search.SearchService;

/** Tests of the handler logic without a network: the "client" is a fake {@link Sink}. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WsSearchHandlerTest {

  @TempDir Path dataDir;

  private ThreadPool pool;
  private WsSearchHandler handler;

  /** Records every message and fails the test run if two threads send at the same time. */
  private static final class RecordingSink implements WsSearchHandler.Sink {
    private final List<String> messages = new ArrayList<>(); // guarded by itself
    private int inside; // threads inside send() now; guarded by messages
    private int maxInside; // guarded by messages
    private volatile int failAfter = Integer.MAX_VALUE; // send number that throws
    private volatile boolean open = true;

    @Override
    public void send(String text) throws Exception {
      synchronized (messages) {
        inside++;
        maxInside = Math.max(maxInside, inside);
      }
      try {
        Thread.sleep(2); // widens the window in which a second sender could get in
        synchronized (messages) {
          if (messages.size() >= failAfter) {
            throw new IOException("connection reset");
          }
          messages.add(text);
        }
      } finally {
        synchronized (messages) {
          inside--;
        }
      }
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    List<String> copy() {
      synchronized (messages) {
        return new ArrayList<>(messages);
      }
    }

    int maxInside() {
      synchronized (messages) {
        return maxInside;
      }
    }
  }

  @BeforeEach
  void setUp() throws IOException {
    Files.writeString(dataDir.resolve("whale.txt"), "white whale harpoon ahab");
    Files.writeString(dataDir.resolve("holmes.txt"), "sherlock holmes detective");
    Tokenizer tokenizer = new Tokenizer();
    IndexManager manager = new IndexManager(tokenizer);
    manager.rebuild(dataDir, 2);
    QueryExpander expander = new QueryExpander(prompt -> "[\"ship\"]", tokenizer, 5_000, 0);
    pool = new ThreadPool(4);
    handler = new WsSearchHandler(new SearchService(manager, tokenizer, dataDir, expander), pool);
  }

  @AfterEach
  void tearDown() {
    pool.shutdown();
  }

  private void waitUntilIdle() throws InterruptedException {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while ((pool.activeCount() > 0 || pool.queueSize() > 0) && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(0, pool.activeCount());
  }

  @Test
  void messagesOfOneConnectionAreSentOneByOneInTheRightOrder() throws Exception {
    RecordingSink sink = new RecordingSink();
    Object session = new Object();
    handler.onConnect(session, sink);

    for (int i = 0; i < 5; i++) { // five queries sent back to back, no waiting for answers
      handler.onMessage(session, "{\"query\": \"harpoon\"}");
    }
    waitUntilIdle();

    List<String> messages = sink.copy();
    assertEquals(15, messages.size());
    for (int i = 0; i < 15; i += 3) {
      assertTrue(messages.get(i).contains("\"stage\":\"query_expansion\""), messages.get(i));
      assertTrue(messages.get(i + 1).contains("\"stage\":\"keyword_search\""));
      assertEquals("{\"stage\":\"done\"}", messages.get(i + 2));
    }
    assertEquals(1, sink.maxInside(), "two threads wrote to the same session at once");
  }

  @Test
  void aClientThatFailsInTheMiddleStopsThePipelineQuietly() throws Exception {
    RecordingSink sink = new RecordingSink();
    sink.failAfter = 1; // the first message is delivered, the second one fails
    Object session = new Object();
    handler.onConnect(session, sink);

    handler.onMessage(session, "{\"query\": \"harpoon\"}");
    handler.onMessage(session, "{\"query\": \"sherlock\"}");
    waitUntilIdle();

    assertEquals(1, sink.copy().size(), "nothing is sent after the failure");
    assertEquals(0, pool.queueSize());

    // The pool is still alive and serves other connections.
    RecordingSink other = new RecordingSink();
    Object otherSession = new Object();
    handler.onConnect(otherSession, other);
    handler.onMessage(otherSession, "{\"query\": \"sherlock\"}");
    waitUntilIdle();
    assertEquals(3, other.copy().size());
  }

  @Test
  void aClosedSessionGetsNothingAndIsForgotten() throws Exception {
    RecordingSink sink = new RecordingSink();
    Object session = new Object();
    handler.onConnect(session, sink);
    assertEquals(1, handler.openConnections());

    sink.open = false;
    handler.onMessage(session, "{\"query\": \"harpoon\"}");
    waitUntilIdle();
    assertTrue(sink.copy().isEmpty());

    handler.onClose(session);
    assertEquals(0, handler.openConnections());
    handler.onMessage(session, "{\"query\": \"harpoon\"}"); // must not throw
    assertFalse(sink.copy().size() > 0);
  }

  @Test
  void tooManyWaitingQueriesAreRefusedWithAnError() throws Exception {
    Tokenizer tokenizer = new Tokenizer();
    IndexManager manager = new IndexManager(tokenizer);
    // The model blocks until released, so the queue of one connection fills up.
    Object gate = new Object();
    boolean[] open = {false};
    QueryExpander expander =
        new QueryExpander(
            prompt -> {
              synchronized (gate) {
                while (!open[0]) {
                  try {
                    gate.wait(5_000);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                  }
                }
              }
              return "[\"ship\"]";
            },
            tokenizer,
            5_000,
            0);
    WsSearchHandler slowHandler =
        new WsSearchHandler(new SearchService(manager, tokenizer, dataDir, expander), pool);
    RecordingSink sink = new RecordingSink();
    Object session = new Object();
    slowHandler.onConnect(session, sink);

    int total = WsSearchHandler.MAX_PENDING + 5;
    for (int i = 0; i < total; i++) {
      slowHandler.onMessage(session, "{\"query\": \"harpoon\"}");
    }
    List<String> refused = sink.copy();
    assertTrue(refused.size() >= 2, "the overflow must be answered at once: " + refused);
    assertTrue(refused.get(0).contains("\"stage\":\"error\""));
    assertEquals("{\"stage\":\"done\"}", refused.get(1));

    synchronized (gate) {
      open[0] = true;
      gate.notifyAll();
    }
    waitUntilIdle();
  }
}
