package ua.coursework.engine.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ua.coursework.engine.index.Tokenizer;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class QueryExpanderTest {

  private static final Tokenizer TOKENIZER = new Tokenizer();

  /** An expander whose "model" always answers with the given text. */
  private static QueryExpander answering(String answer) {
    return new QueryExpander(prompt -> answer, TOKENIZER, 1_000, 16);
  }

  private static QueryExpander failing() {
    return new QueryExpander(
        prompt -> {
          throw new OllamaException("down");
        },
        TOKENIZER,
        1_000,
        16);
  }

  // ---------------------------------------------------------------- parsing

  @Test
  void parsesAPlainJsonArray() {
    assertEquals(
        List.of("harpoon", "leviathan", "ocean"),
        answering("[\"harpoon\", \"leviathan\", \"ocean\"]").expand("white whale"));
  }

  @Test
  void parsesAJsonArrayWrappedInTextAndACodeBlock() {
    String answer = "Sure! Here you go:\n```json\n[\"harpoon\", \"ocean\"]\n```\nHope it helps.";
    assertEquals(List.of("harpoon", "ocean"), answering(answer).expand("white whale"));
  }

  @Test
  void parsesACommaSeparatedList() {
    assertEquals(
        List.of("harpoon", "ocean", "sailor"),
        answering("harpoon, ocean, sailor").expand("white whale"));
  }

  @Test
  void parsesBulletAndNumberedLines() {
    assertEquals(
        List.of("harpoon", "ocean", "sailor", "captain"),
        answering("- harpoon\n* ocean\n• sailor\n1. captain").expand("white whale"));
    assertEquals(
        List.of("harpoon", "ocean", "sailor"),
        answering("1) harpoon\n2) ocean\n3) sailor").expand("white whale"));
  }

  @Test
  void parsesBrokenJsonByFallingBackToSplitting() {
    // the closing quote is missing, so this is not valid JSON
    assertEquals(
        List.of("harpoon", "ocean"), answering("[\"harpoon\", \"ocean]").expand("white whale"));
  }

  @Test
  void parsesQuotedItemsWithoutBrackets() {
    assertEquals(
        List.of("harpoon", "ocean"), answering("\"harpoon\", 'ocean'").expand("white whale"));
  }

  @Test
  void parsesSemicolonSeparatedItems() {
    assertEquals(List.of("harpoon", "ocean"), answering("harpoon; ocean").expand("white whale"));
  }

  @Test
  void itemsGoThroughTheTokenizerSoPhrasesAreSplitAndStopWordsAreDropped() {
    assertEquals(
        List.of("sea", "monster", "ship"),
        answering("[\"Sea Monster\", \"THE\", \"a ship\"]").expand("white whale"));
  }

  @Test
  void duplicatesAndWordsAlreadyInTheQueryAreRemoved() {
    assertEquals(
        List.of("harpoon", "ocean"),
        answering("[\"whale\", \"Harpoon\", \"harpoon\", \"WHITE\", \"ocean\"]")
            .expand("white whale"));
  }

  @Test
  void atMostEightTermsAreReturned() {
    String answer = "[\"aa\",\"bb\",\"cc\",\"dd\",\"ee\",\"ff\",\"gg\",\"hh\",\"ii\",\"jj\",\"kk\"]";
    List<String> terms = answering(answer).expand("white whale");
    assertEquals(List.of("aa", "bb", "cc", "dd", "ee", "ff", "gg", "hh"), terms);
  }

  @Test
  void aSentenceInsteadOfKeywordsIsIgnoredInThePlainTextFallback() {
    String answer = "I am sorry but I really cannot help with that request today";
    assertEquals(List.of(), answering(answer).expand("white whale"));
  }

  @Test
  void nonTextElementsOfAJsonArrayAreIgnored() {
    assertEquals(
        List.of("harpoon", "ocean"),
        answering("[\"harpoon\", 42, null, {\"a\":1}, \"ocean\"]").expand("white whale"));
  }

  @Test
  void emptyOrBlankAnswersGiveAnEmptyList() {
    assertEquals(List.of(), answering("").expand("white whale"));
    assertEquals(List.of(), answering("   \n ").expand("white whale"));
    assertEquals(List.of(), answering("[]").expand("white whale"));
    assertEquals(List.of(), answering("[\"whale\"]").expand("white whale"));
  }

  @Test
  void theReturnedListIsNotModifiable() {
    List<String> terms = answering("[\"harpoon\"]").expand("white whale");
    boolean modifiable;
    try {
      terms.add("x");
      modifiable = true;
    } catch (UnsupportedOperationException e) {
      modifiable = false;
    }
    assertFalse(modifiable);
  }

  // ---------------------------------------------------------------- fallback

  @Test
  void whenTheModelFailsTheResultIsAnEmptyListAndNothingIsThrown() {
    assertEquals(List.of(), failing().expand("white whale"));
  }

  @Test
  void aRuntimeErrorInsideTheGeneratorIsAlsoAFallback() {
    QueryExpander expander =
        new QueryExpander(
            prompt -> {
              throw new IllegalStateException("bug");
            },
            TOKENIZER,
            1_000,
            16);
    assertEquals(List.of(), expander.expand("white whale"));
  }

  @Test
  void aQueryWithoutUsableWordsDoesNotCallTheModel() {
    AtomicInteger calls = new AtomicInteger();
    QueryExpander expander =
        new QueryExpander(
            prompt -> {
              calls.incrementAndGet();
              return "[\"x\"]";
            },
            TOKENIZER,
            1_000,
            16);

    assertEquals(List.of(), expander.expand("the and of"));
    assertEquals(List.of(), expander.expand("   "));
    assertEquals(List.of(), expander.expand(null));
    assertEquals(0, calls.get());
  }

  @Test
  void theSemaphorePermitIsReleasedAfterAFailure() {
    AtomicInteger calls = new AtomicInteger();
    QueryExpander expander =
        new QueryExpander(
            prompt -> {
              if (calls.incrementAndGet() == 1) {
                throw new OllamaException("first call fails");
              }
              return "[\"harpoon\"]";
            },
            TOKENIZER,
            200,
            0);

    assertEquals(List.of(), expander.expand("white whale"));
    assertEquals(List.of("harpoon"), expander.expand("white whale"));
  }

  @Test
  void worksWithTheRealClientAgainstAFakeServer() throws Exception {
    try (FakeOllama fake =
        new FakeOllama()
            .on("/api/generate", 200, "{\"response\":\"[\\\"harpoon\\\",\\\"ocean\\\"]\"}")
            .start()) {
      OllamaClient client =
          new OllamaClient(fake.url(), "m", Duration.ofSeconds(5), Duration.ofSeconds(5));
      assertEquals(
          List.of("harpoon", "ocean"), new QueryExpander(client, TOKENIZER).expand("white whale"));

      String prompt = new com.fasterxml.jackson.databind.ObjectMapper()
          .readTree(fake.lastBody("/api/generate")).get("prompt").asText();
      assertTrue(prompt.contains("white whale"));
      assertTrue(prompt.contains("JSON array"));
    }
  }

  @Test
  void aSlowOrBrokenServerGivesAnEmptyListNotAnException() throws Exception {
    try (FakeOllama fake = new FakeOllama().on("/api/generate", 500, "oops").start()) {
      OllamaClient client =
          new OllamaClient(fake.url(), "m", Duration.ofSeconds(5), Duration.ofSeconds(5));
      assertEquals(List.of(), new QueryExpander(client, TOKENIZER).expand("white whale"));
    }
    try (FakeOllama fake =
        new FakeOllama()
            .on(
                "/api/generate",
                (body, responder) -> {
                  Thread.sleep(1_500);
                  responder.send(200, "{\"response\":\"[\\\"late\\\"]\"}");
                })
            .start()) {
      OllamaClient client =
          new OllamaClient(fake.url(), "m", Duration.ofMillis(300), Duration.ofSeconds(5));
      assertEquals(List.of(), new QueryExpander(client, TOKENIZER).expand("white whale"));
    }
  }

  // ---------------------------------------------------------------- semaphore

  @Test
  void neverMoreThanOneGenerationRunsAtATime() throws Exception {
    AtomicInteger running = new AtomicInteger();
    AtomicInteger maxRunning = new AtomicInteger();
    AtomicInteger calls = new AtomicInteger();
    QueryExpander expander =
        new QueryExpander(
            prompt -> {
              int now = running.incrementAndGet();
              maxRunning.accumulateAndGet(now, Math::max);
              calls.incrementAndGet();
              try {
                Thread.sleep(40);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              running.decrementAndGet();
              return "[\"harpoon\"]";
            },
            TOKENIZER,
            30_000,
            0);

    int threads = 8;
    List<Thread> workers = new ArrayList<>();
    List<List<String>> results = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      String query = "query" + i;
      Thread worker =
          new Thread(
              () -> {
                List<String> terms = expander.expand(query);
                synchronized (results) {
                  results.add(terms);
                }
              });
      workers.add(worker);
    }
    workers.forEach(Thread::start);
    for (Thread worker : workers) {
      worker.join();
    }

    assertEquals(threads, calls.get(), "everybody waited and got a turn");
    assertEquals(1, maxRunning.get());
    assertEquals(threads, results.size());
    for (List<String> terms : results) {
      assertEquals(List.of("harpoon"), terms);
    }
  }

  @Test
  void aRequestThatCannotGetThePermitInTimeFallsBackInsteadOfWaiting() throws Exception {
    CountDownLatch insideGenerator = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    QueryExpander expander =
        new QueryExpander(
            prompt -> {
              calls.incrementAndGet();
              insideGenerator.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return "[\"harpoon\"]";
            },
            TOKENIZER,
            100,
            0);

    List<String> first = new ArrayList<>();
    Thread slow = new Thread(() -> first.addAll(expander.expand("white whale")));
    slow.start();
    assertTrue(insideGenerator.await(10, TimeUnit.SECONDS));

    long start = System.nanoTime();
    List<String> second = expander.expand("pride prejudice");
    long millis = (System.nanoTime() - start) / 1_000_000;

    assertEquals(List.of(), second);
    assertTrue(millis < 5_000, "must give up after the short wait: " + millis);
    assertEquals(1, calls.get(), "the second request never reached the model");

    release.countDown();
    slow.join(10_000);
    assertEquals(List.of("harpoon"), first);
    // the permit is free again
    assertEquals(List.of("harpoon"), expander.expand("pride prejudice"));
  }

  // ---------------------------------------------------------------- cache

  @Test
  void theSameQueryIsAskedOnlyOnceAndDifferentSpellingsShareTheEntry() {
    AtomicInteger calls = new AtomicInteger();
    QueryExpander expander =
        new QueryExpander(
            prompt -> {
              calls.incrementAndGet();
              return "[\"harpoon\"]";
            },
            TOKENIZER,
            1_000,
            16);

    assertEquals(List.of("harpoon"), expander.expand("white whale"));
    assertEquals(List.of("harpoon"), expander.expand("White   WHALE!"));
    assertEquals(1, calls.get());
  }

  @Test
  void failuresAreNotCached() {
    AtomicInteger calls = new AtomicInteger();
    QueryExpander expander =
        new QueryExpander(
            prompt -> {
              if (calls.incrementAndGet() == 1) {
                throw new OllamaException("down");
              }
              return "[\"harpoon\"]";
            },
            TOKENIZER,
            1_000,
            16);

    assertEquals(List.of(), expander.expand("white whale"));
    assertEquals(List.of("harpoon"), expander.expand("white whale"));
    assertEquals(2, calls.get());
  }

  @Test
  void theCacheForgetsTheLeastRecentlyUsedQuery() {
    AtomicInteger calls = new AtomicInteger();
    QueryExpander expander =
        new QueryExpander(
            prompt -> {
              calls.incrementAndGet();
              return "[\"harpoon\"]";
            },
            TOKENIZER,
            1_000,
            2);

    expander.expand("alpha"); // cache: alpha
    expander.expand("bravo"); // cache: alpha, bravo
    expander.expand("alpha"); // hit, alpha is now the most recent
    expander.expand("charlie"); // cache full: bravo is dropped
    assertEquals(3, calls.get());

    expander.expand("alpha"); // still cached
    assertEquals(3, calls.get());
    expander.expand("bravo"); // was dropped: asked again
    assertEquals(4, calls.get());
  }

  @Test
  void aCacheSizeOfZeroTurnsTheCacheOff() {
    AtomicInteger calls = new AtomicInteger();
    QueryExpander expander =
        new QueryExpander(
            prompt -> {
              calls.incrementAndGet();
              return "[\"harpoon\"]";
            },
            TOKENIZER,
            1_000,
            0);

    expander.expand("white whale");
    expander.expand("white whale");
    assertEquals(2, calls.get());
  }
}
