package ua.coursework.engine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ua.coursework.engine.concurrent.ThreadPool;
import ua.coursework.engine.index.IndexManager;
import ua.coursework.engine.index.Tokenizer;
import ua.coursework.engine.llm.OllamaClient;
import ua.coursework.engine.llm.OllamaException;
import ua.coursework.engine.llm.QueryExpander;
import ua.coursework.engine.llm.TextGenerator;
import ua.coursework.engine.search.SearchService;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WsSearchTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long WAIT_MS = 10_000;

  @TempDir Path dataDir;

  private Javalin app;
  private ThreadPool pool;

  @AfterEach
  void stopServer() {
    if (app != null) {
      app.stop();
    }
    if (pool != null) {
      pool.shutdown();
    }
  }

  private void writeBooks() throws IOException {
    Files.writeString(dataDir.resolve("whale.txt"), "white whale harpoon captain ahab sea ship");
    Files.writeString(dataDir.resolve("holmes.txt"), "sherlock holmes detective baker street");
    Files.writeString(dataDir.resolve("pride.txt"), "pride prejudice marriage elizabeth darcy");
    Files.writeString(dataDir.resolve("ocean.txt"), "ocean sailor storm wave");
  }

  private int startServer(
      TextGenerator generator, int poolThreads, long permitWaitMillis, boolean indexBooks)
      throws IOException {
    Tokenizer tokenizer = new Tokenizer();
    IndexManager manager = new IndexManager(tokenizer);
    if (indexBooks) {
      writeBooks();
      manager.rebuild(dataDir, 2);
    }
    QueryExpander expander = new QueryExpander(generator, tokenizer, permitWaitMillis, 0);
    pool = new ThreadPool(poolThreads);
    app =
        new ApiServer(
                new OllamaClient("http://localhost:1"),
                manager,
                new SearchService(manager, tokenizer, dataDir, expander),
                dataDir,
                pool)
            .create()
            .start(0);
    return app.port();
  }

  private static JsonNode parse(String message) throws IOException {
    return JSON.readTree(message);
  }

  private static Set<String> fields(JsonNode node) {
    Set<String> names = new java.util.TreeSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static String query(String text) {
    return "{\"query\": \"" + text + "\"}";
  }

  private static void waitUntilPoolIsIdle(ThreadPool pool) throws InterruptedException {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while ((pool.activeCount() > 0 || pool.queueSize() > 0) && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertEquals(0, pool.activeCount());
    assertEquals(0, pool.queueSize());
  }

  @Test
  void threeStagesInOrderWithTheDocumentedShape() throws Exception {
    int port = startServer(prompt -> "[\"ship\", \"sea\"]", 4, 5_000, true);

    try (WsTestClient client = WsTestClient.connect(port)) {
      client.send(query("white whale"));
      List<String> messages = client.readUntilDone(WAIT_MS);

      assertEquals(3, messages.size());

      JsonNode expansion = parse(messages.get(0));
      assertEquals(Set.of("stage", "data"), fields(expansion));
      assertEquals("query_expansion", expansion.get("stage").asText());
      assertEquals(Set.of("original", "expanded_terms"), fields(expansion.get("data")));
      assertEquals("white whale", expansion.get("data").get("original").asText());
      assertEquals(
          List.of("ship", "sea"),
          List.of(
              expansion.get("data").get("expanded_terms").get(0).asText(),
              expansion.get("data").get("expanded_terms").get(1).asText()));

      JsonNode search = parse(messages.get(1));
      assertEquals("keyword_search", search.get("stage").asText());
      assertEquals(Set.of("results", "time_ms"), fields(search.get("data")));
      assertTrue(search.get("data").get("time_ms").canConvertToLong());
      JsonNode results = search.get("data").get("results");
      assertTrue(results.size() >= 1);
      assertEquals(Set.of("doc_id", "score", "snippet"), fields(results.get(0)));
      assertEquals("whale.txt", results.get(0).get("doc_id").asText());
      assertEquals(1.0, results.get(0).get("score").asDouble());

      assertEquals("{\"stage\":\"done\"}", messages.get(2));
    }
  }

  @Test
  void timeMsMeasuresOnlyTheSearchStage() throws Exception {
    int port =
        startServer(
            prompt -> {
              try {
                Thread.sleep(600);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return "[\"ship\"]";
            },
            2,
            5_000,
            true);

    try (WsTestClient client = WsTestClient.connect(port)) {
      long start = System.nanoTime();
      client.send(query("harpoon"));
      List<String> messages = client.readUntilDone(WAIT_MS);
      long totalMs = (System.nanoTime() - start) / 1_000_000;

      long timeMs = parse(messages.get(1)).get("data").get("time_ms").asLong();
      assertTrue(totalMs >= 600, "the model delay must be part of the total time: " + totalMs);
      assertTrue(timeMs < 400, "time_ms must not include the model delay: " + timeMs);
    }
  }

  @Test
  void whenTheModelFailsExpandedTermsAreEmptyAndTheOriginalQueryIsSearched() throws Exception {
    TextGenerator broken =
        prompt -> {
          throw new OllamaException("Ollama is down");
        };
    int port = startServer(broken, 2, 5_000, true);

    try (WsTestClient client = WsTestClient.connect(port)) {
      client.send(query("harpoon"));
      List<String> messages = client.readUntilDone(WAIT_MS);

      assertEquals(3, messages.size());
      JsonNode expanded = parse(messages.get(0)).get("data").get("expanded_terms");
      assertTrue(expanded.isArray());
      assertEquals(0, expanded.size());
      JsonNode results = parse(messages.get(1)).get("data").get("results");
      assertEquals("whale.txt", results.get(0).get("doc_id").asText());
      assertEquals("{\"stage\":\"done\"}", messages.get(2));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not json at all",
        "",
        "{\"query\": \"\"}",
        "{\"query\": \"   \"}",
        "{}",
        "[]",
        "{\"query\": 42}",
        "{\"query\": null}",
        "\"just a string\""
      })
  void badInputGivesErrorThenDoneAndTheConnectionStaysOpen(String badMessage) throws Exception {
    int port = startServer(prompt -> "[\"ship\"]", 2, 5_000, true);

    try (WsTestClient client = WsTestClient.connect(port)) {
      client.send(badMessage);
      List<String> messages = client.readUntilDone(WAIT_MS);

      assertEquals(2, messages.size(), messages.toString());
      JsonNode error = parse(messages.get(0));
      assertEquals("error", error.get("stage").asText());
      assertEquals(Set.of("message"), fields(error.get("data")));
      assertFalse(error.get("data").get("message").asText().isBlank());
      assertEquals("{\"stage\":\"done\"}", messages.get(1));
      assertFalse(client.isClosed());

      client.send(query("harpoon"));
      List<String> next = client.readUntilDone(WAIT_MS);
      assertEquals(3, next.size());
      assertEquals("query_expansion", parse(next.get(0)).get("stage").asText());
    }
  }

  @Test
  void emptyIndexGivesNormalStagesWithEmptyResults() throws Exception {
    int port = startServer(prompt -> "[\"ship\"]", 2, 5_000, false);

    try (WsTestClient client = WsTestClient.connect(port)) {
      client.send(query("white whale"));
      List<String> messages = client.readUntilDone(WAIT_MS);

      assertEquals(3, messages.size());
      assertEquals("query_expansion", parse(messages.get(0)).get("stage").asText());
      JsonNode search = parse(messages.get(1));
      assertEquals("keyword_search", search.get("stage").asText());
      assertEquals(0, search.get("data").get("results").size());
      assertEquals("{\"stage\":\"done\"}", messages.get(2));
    }
  }

  @Test
  void twoQueriesOnOneConnectionAreAnsweredOneAfterAnotherInOrder() throws Exception {
    int port = startServer(prompt -> "[\"ship\"]", 4, 5_000, true);

    try (WsTestClient client = WsTestClient.connect(port)) {
      client.send(query("harpoon"));
      client.send(query("sherlock"));
      List<String> first = client.readUntilDone(WAIT_MS);
      List<String> second = client.readUntilDone(WAIT_MS);

      assertEquals("harpoon", parse(first.get(0)).get("data").get("original").asText());
      assertEquals("sherlock", parse(second.get(0)).get("data").get("original").asText());
      assertEquals(3, first.size());
      assertEquals(3, second.size());
    }
  }

  @Test
  void twentyClientsAtTheSameTimeGetCompleteAndCorrectAnswers() throws Exception {
    int port =
        startServer(
            prompt -> {
              try {
                Thread.sleep(20);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return "[\"ship\"]";
            },
            4,
            30_000,
            true);
    String[] words = {"harpoon", "sherlock", "darcy", "storm"};
    String[] expectedDocs = {"whale.txt", "holmes.txt", "pride.txt", "ocean.txt"};
    int clients = 20;
    List<String> problems = new ArrayList<>();
    CountDownLatch go = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();

    for (int i = 0; i < clients; i++) {
      int n = i;
      Thread thread =
          new Thread(
              () -> {
                String word = words[n % 4];
                try (WsTestClient client = WsTestClient.connect(port)) {
                  go.await();
                  client.send(query(word));
                  List<String> messages = client.readUntilDone(30_000);
                  if (messages.size() != 3) {
                    throw new AssertionError("expected 3 messages, got " + messages);
                  }
                  JsonNode expansion = parse(messages.get(0));
                  JsonNode search = parse(messages.get(1));
                  if (!"query_expansion".equals(expansion.get("stage").asText())
                      || !word.equals(expansion.get("data").get("original").asText())
                      || !"keyword_search".equals(search.get("stage").asText())
                      || !expectedDocs[n % 4].equals(
                          search.get("data").get("results").get(0).get("doc_id").asText())
                      || !"{\"stage\":\"done\"}".equals(messages.get(2))) {
                    throw new AssertionError("wrong answer for '" + word + "': " + messages);
                  }
                } catch (Throwable t) {
                  synchronized (problems) {
                    problems.add("client " + n + ": " + t);
                  }
                }
              },
              "test-client-" + i);
      thread.start();
      threads.add(thread);
    }
    go.countDown();
    for (Thread thread : threads) {
      thread.join(45_000);
    }

    synchronized (problems) {
      assertTrue(problems.isEmpty(), problems.toString());
    }
  }

  @Test
  void aSlowModelDoesNotBlockOtherConnections() throws Exception {
    CountDownLatch slowStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    TextGenerator generator =
        prompt -> {
          if (prompt.contains("sherlock")) {
            slowStarted.countDown();
            try {
              release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          return "[\"ship\"]";
        };
    int port = startServer(generator, 2, 100, true);

    try (WsTestClient slow = WsTestClient.connect(port);
        WsTestClient fast = WsTestClient.connect(port)) {
      slow.send(query("sherlock"));
      assertTrue(slowStarted.await(10, TimeUnit.SECONDS));

      fast.send(query("harpoon"));
      List<String> fastMessages = fast.readUntilDone(5_000);
      assertEquals(3, fastMessages.size());
      assertNull(slow.next(200), "the slow query has not produced anything yet");

      release.countDown();
      List<String> slowMessages = slow.readUntilDone(WAIT_MS);
      assertEquals(3, slowMessages.size());
      assertEquals("holmes.txt", parse(slowMessages.get(1)).get("data").get("results").get(0)
          .get("doc_id").asText());
    } finally {
      release.countDown();
    }
  }

  @Test
  void clientDroppingInTheMiddleOfTheWorkDoesNotBreakTheServer() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    TextGenerator generator =
        prompt -> {
          calls.incrementAndGet();
          started.countDown();
          try {
            release.await(20, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return "[\"ship\"]";
        };
    int port = startServer(generator, 2, 5_000, true);

    WsTestClient dropping = WsTestClient.connect(port);
    dropping.send(query("harpoon"));
    assertTrue(started.await(10, TimeUnit.SECONDS));
    dropping.abort(); // client leaves while the model thinks
    Thread.sleep(300);
    release.countDown();

    waitUntilPoolIsIdle(pool);

    try (WsTestClient other = WsTestClient.connect(port)) {
      other.send(query("sherlock"));
      List<String> messages = other.readUntilDone(WAIT_MS);
      assertEquals(3, messages.size());
      assertEquals("keyword_search", parse(messages.get(1)).get("stage").asText());
    }
  }
}
