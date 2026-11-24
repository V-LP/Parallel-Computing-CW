package ua.coursework.engine.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import ua.coursework.engine.index.IndexManager;
import ua.coursework.engine.index.Tokenizer;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SearchServiceTest {

  @TempDir Path dataDir;

  private void write(String name, String content) throws IOException {
    Path file = dataDir.resolve(name);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private SearchService indexAndCreateService() throws IOException {
    IndexManager manager = new IndexManager(new Tokenizer());
    manager.rebuild(dataDir, 2);
    return new SearchService(manager, new Tokenizer(), dataDir);
  }

  private void writeWhaleFiles() throws IOException {
    write("moby.txt", "whale whale whale harpoon ship sea");
    write("ahab.txt", "whale ship captain");
    write("fish.txt", "harpoon fish");
    write("garden.txt", "garden flowers rose");
    write("city.txt", "city traffic cars");
    write("ocean.txt", "sea ship ocean");
  }

  private SearchService whaleCorpus() throws IOException {
    writeWhaleFiles();
    return indexAndCreateService();
  }

  private static List<String> ids(List<SearchHit> hits) {
    return hits.stream().map(SearchHit::docId).toList();
  }

  @Test
  void documentWithMostQueryTermsComesFirstAndOnlyMatchingDocumentsAreReturned()
      throws IOException {
    SearchService service = whaleCorpus();

    List<SearchHit> hits = service.search("whale harpoon", 10);

    assertEquals("moby.txt", hits.get(0).docId());
    assertEquals(1.0, hits.get(0).score());
    assertEquals(3, hits.size(), "OR semantics: every document with at least one term");
    assertEquals(
        List.of("ahab.txt", "fish.txt", "moby.txt"), ids(hits).stream().sorted().toList());
    for (SearchHit hit : hits.subList(1, hits.size())) {
      assertTrue(hit.score() > 0 && hit.score() < 1.0, hit.docId() + " " + hit.score());
    }
  }

  @Test
  void whenEverythingElseIsEqualTheShorterDocumentWins() throws IOException {
    SearchService service = whaleCorpus();

    assertEquals(
        List.of("moby.txt", "fish.txt", "ahab.txt"), ids(service.search("whale harpoon", 10)));
  }

  @Test
  void higherTermFrequencyRanksHigher() throws IOException {
    write("many.txt", "alpha alpha alpha alpha alpha omega");
    write("once.txt", "alpha beta gamma delta epsilon zeta");
    SearchService service = indexAndCreateService();

    assertEquals(List.of("many.txt", "once.txt"), ids(service.search("alpha", 10)));
  }

  @Test
  void rareTermCountsMoreThanCommonTermAndTiesAreBrokenByPath() throws IOException {
    for (int i = 1; i <= 5; i++) {
      write("c" + i + ".txt", "common aa" + i + " bb" + i + " cc" + i);
    }
    write("rare.txt", "rare xx yy zz");
    SearchService service = indexAndCreateService();

    List<SearchHit> hits = service.search("common rare", 10);

    assertEquals(
        List.of("rare.txt", "c1.txt", "c2.txt", "c3.txt", "c4.txt", "c5.txt"), ids(hits));
    assertEquals(1.0, hits.get(0).score());
    assertEquals(hits.get(1).score(), hits.get(5).score());
  }

  @Test
  void documentMatchingMoreDifferentTermsRanksHigherThanOneMatchingFewButOften()
      throws IOException {
    write("both.txt", "red blue aa bb");
    write("onlyred.txt", "red red cc dd");
    write("green.txt", "green yellow ee ff");
    SearchService service = indexAndCreateService();

    assertEquals(List.of("both.txt", "onlyred.txt"), ids(service.search("red blue", 10)));
  }

  private static String padded(int total, String... words) {
    List<String> all = new ArrayList<>(List.of(words));
    for (int i = 0; all.size() < total; i++) {
      all.add("pad" + i);
    }
    return String.join(" ", all);
  }

  @Test
  void coordinationFactorLetsMoreDifferentTermsBeatOneVeryFrequentTerm() throws IOException {
    // coord must beat a larger plain sum
    write("a.txt", padded(10, "red", "blue"));
    write("b.txt", padded(10, "red", "red", "red", "red", "red"));
    for (int i = 1; i <= 3; i++) {
      write("blue" + i + ".txt", padded(10, "blue"));
    }
    for (int i = 1; i <= 15; i++) {
      write("f" + i + ".txt", padded(10, "filler" + i));
    }
    SearchService service = indexAndCreateService();

    List<SearchHit> hits = service.search("red blue", 10);

    assertEquals("a.txt", hits.get(0).docId());
    assertEquals("b.txt", hits.get(1).docId());
    assertEquals(5, hits.size());
  }

  @Test
  void termInEveryDocumentStillCounts() throws IOException {
    // smoothed idf keeps common terms positive
    for (int i = 1; i <= 15; i++) {
      write(String.format("doc%02d.txt", i), "banana ".repeat(i) + "filler ".repeat(20 - i));
    }
    SearchService service = indexAndCreateService();

    List<SearchHit> hits = service.search("banana", 100);

    assertEquals(15, hits.size());
    assertEquals("doc15.txt", hits.get(0).docId());
    assertEquals("doc01.txt", hits.get(14).docId());
  }

  @Test
  void topKIsThePrefixOfTheFullRanking() throws IOException {
    for (int i = 1; i <= 15; i++) {
      write(String.format("doc%02d.txt", i), "banana ".repeat(i) + "filler ".repeat(20 - i));
    }
    SearchService service = indexAndCreateService();

    List<SearchHit> all = service.search("banana", 100);

    assertEquals(List.of("doc15.txt", "doc14.txt", "doc13.txt"), ids(service.search("banana", 3)));
    assertEquals(all.subList(0, 3), service.search("banana", 3));
    assertEquals(all.subList(0, 1), service.search("banana", 1));
    assertEquals(10, service.search("banana", SearchService.DEFAULT_TOP_K).size());
    for (int i = 1; i < all.size(); i++) {
      assertTrue(all.get(i - 1).score() >= all.get(i).score());
    }
  }

  @Test
  void scoresAreBetweenZeroAndOneAndRoundedToFourDecimals() throws IOException {
    SearchService service = whaleCorpus();

    for (SearchHit hit : service.search("whale harpoon ship sea", 10)) {
      assertTrue(hit.score() > 0 && hit.score() <= 1.0, hit.toString());
      double scaled = hit.score() * 10_000;
      assertTrue(Math.abs(scaled - Math.round(scaled)) < 1e-6, "not rounded: " + hit.score());
    }
  }

  @Test
  void resultIsDeterministicForIdenticalDocuments() throws IOException {
    write("b.txt", "same words here");
    write("a.txt", "same words here");
    write("c.txt", "same words here");
    SearchService service = indexAndCreateService();

    assertEquals(List.of("a.txt", "b.txt", "c.txt"), ids(service.search("words", 10)));
    assertEquals(List.of("a.txt", "b.txt"), ids(service.search("words", 2)));
  }

  @Test
  void stopWordsInTheQueryAreIgnored() throws IOException {
    SearchService service = whaleCorpus();

    assertEquals(List.of(), service.search("the and of", 10));
    assertEquals(service.search("whale harpoon", 10), service.search("the whale and the harpoon", 10));
  }

  @Test
  void unknownTermsMatchNothingAndDoNotChangeTheRanking() throws IOException {
    SearchService service = whaleCorpus();

    assertEquals(List.of(), service.search("zzzzqqqq", 10));
    assertEquals(service.search("whale", 10), service.search("whale zzzzqqqq", 10));
  }

  @Test
  void queryIsCaseInsensitiveAndRepeatedTermsCountOnce() throws IOException {
    SearchService service = whaleCorpus();

    assertEquals(service.search("whale harpoon", 10), service.search("WHALE Harpoon", 10));
    assertEquals(service.search("whale", 10), service.search("whale whale whale", 10));
  }

  @Test
  void emptyOrNeverBuiltIndexGivesNoResults() throws IOException {
    SearchService neverBuilt =
        new SearchService(new IndexManager(new Tokenizer()), new Tokenizer(), dataDir);
    assertEquals(List.of(), neverBuilt.search("whale", 10));

    assertEquals(List.of(), indexAndCreateService().search("whale", 10));
  }

  @Test
  void invalidArgumentsAreRejected() throws IOException {
    SearchService service = whaleCorpus();

    assertThrows(IllegalArgumentException.class, () -> service.search("whale", 0));
    assertThrows(IllegalArgumentException.class, () -> service.keywordSearch(List.of("whale"), -1));
    assertThrows(NullPointerException.class, () -> service.keywordSearch(null, 5));
    assertThrows(NullPointerException.class, () -> service.search(null, 5));
    assertThrows(IllegalArgumentException.class, () -> new WeightedTerm("", 1.0));
    assertThrows(IllegalArgumentException.class, () -> new WeightedTerm("x", 0));
    assertThrows(IllegalArgumentException.class, () -> new WeightedTerm("x", -1));
    assertThrows(IllegalArgumentException.class, () -> new WeightedTerm("x", Double.NaN));
  }

  @Test
  void termWeightsChangeTheRanking() throws IOException {
    write("w.txt", "whale xx yy");
    write("h.txt", "harpoon xx yy");
    write("other1.txt", "ship sea");
    write("other2.txt", "city cars");
    SearchService service = indexAndCreateService();

    List<SearchHit> whaleHeavy =
        service.keywordSearchWeighted(
            List.of(new WeightedTerm("whale", 1.0), new WeightedTerm("harpoon", 0.1)), 10);
    List<SearchHit> harpoonHeavy =
        service.keywordSearchWeighted(
            List.of(new WeightedTerm("whale", 0.1), new WeightedTerm("harpoon", 1.0)), 10);
    List<SearchHit> equal =
        service.keywordSearchWeighted(
            List.of(new WeightedTerm("whale", 1.0), new WeightedTerm("harpoon", 1.0)), 10);

    assertEquals(List.of("w.txt", "h.txt"), ids(whaleHeavy));
    assertEquals(List.of("h.txt", "w.txt"), ids(harpoonHeavy));
    assertEquals(List.of("h.txt", "w.txt"), ids(equal), "a tie is broken by path");
    assertEquals(equal, service.keywordSearch(List.of("whale", "harpoon"), 10));
  }

  @Test
  void duplicateWeightedTermKeepsTheLargerWeight() throws IOException {
    SearchService service = whaleCorpus();

    assertEquals(
        service.keywordSearchWeighted(List.of(new WeightedTerm("whale", 1.0)), 10),
        service.keywordSearchWeighted(
            List.of(new WeightedTerm("whale", 0.2), new WeightedTerm("WHALE", 1.0)), 10));
  }

  private String snippetOf(SearchService service, String query, String docId) {
    return service.search(query, 10).stream()
        .filter(h -> h.docId().equals(docId))
        .findFirst()
        .orElseThrow()
        .snippet();
  }

  @Test
  void snippetContainsTheTermWithOriginalCase() throws IOException {
    write("story.txt", "Once upon a time there lived a mighty Whale in the deep sea.");
    SearchService service = indexAndCreateService();

    String snippet = snippetOf(service, "whale", "story.txt");

    assertTrue(snippet.contains("Whale"), snippet);
    assertEquals("Once upon a time there lived a mighty Whale in the deep sea.", snippet);
  }

  @Test
  void snippetNormalisesWhitespaceAndLineBreaks() throws IOException {
    write("wrapped.txt", "The   whale\nswam   past\n\n\tthe  boat\r\n");
    SearchService service = indexAndCreateService();

    assertEquals("The whale swam past the boat", snippetOf(service, "whale", "wrapped.txt"));
  }

  @Test
  void snippetIsAboutTwoHundredCharactersAroundTheMatchAndDoesNotCutWords() throws IOException {
    List<String> words = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      words.addAll(List.of("lorem", "ipsum", "dolor", "sit", "amet"));
    }
    words.add("whale");
    for (int i = 0; i < 20; i++) {
      words.addAll(List.of("consectetur", "adipiscing", "elit"));
    }
    StringBuilder text = new StringBuilder();
    int column = 0;
    for (String word : words) {
      if (column > 60) {
        text.append('\n');
        column = 0;
      }
      text.append(word).append(' ');
      column += word.length() + 1;
    }
    write("long.txt", text.toString());
    SearchService service = indexAndCreateService();

    String snippet = snippetOf(service, "whale", "long.txt");

    assertTrue(snippet.contains("whale"));
    assertTrue(snippet.length() >= 150 && snippet.length() <= 230, "length " + snippet.length());
    assertTrue(snippet.startsWith("...") && snippet.endsWith("..."), snippet);
    assertFalse(snippet.contains("\n") || snippet.contains("  "));
    List<String> known =
        List.of("lorem", "ipsum", "dolor", "sit", "amet", "whale", "consectetur", "adipiscing", "elit");
    String[] pieces = snippet.substring(3, snippet.length() - 3).split(" ");
    assertTrue(known.contains(pieces[0]), "cut inside a word: " + pieces[0]);
    assertTrue(known.contains(pieces[pieces.length - 1]), "cut inside a word: " + snippet);
  }

  @Test
  void snippetComesFromTheBookBodyNotFromTheGutenbergHeader() throws IOException {
    write(
        "book.txt",
        String.join(
            "\n",
            "The Project Gutenberg EBook of Tales, header mentions whale here",
            "*** START OF THE PROJECT GUTENBERG EBOOK TALES ***",
            "A different whale story starts in the body.",
            "*** END OF THE PROJECT GUTENBERG EBOOK TALES ***",
            "Footer whale text"));
    SearchService service = indexAndCreateService();

    String snippet = snippetOf(service, "whale", "book.txt");

    assertEquals("A different whale story starts in the body.", snippet);
  }

  @Test
  void snippetMatchesWholeWordsOnly() throws IOException {
    write("words.txt", "category concatenate " + "xx ".repeat(100) + "cat end");
    SearchService service = indexAndCreateService();

    String snippet = snippetOf(service, "cat", "words.txt");

    assertFalse(snippet.contains("category"), snippet);
    assertTrue(snippet.endsWith("cat end"), snippet);
  }

  @Test
  void snippetUsesTheFirstOccurrenceOfAnyQueryTerm() throws IOException {
    write("order.txt", "early harpoon mention " + "xx ".repeat(120) + "late whale mention");
    SearchService service = indexAndCreateService();

    String snippet = snippetOf(service, "whale harpoon", "order.txt");

    assertTrue(snippet.contains("harpoon"), snippet);
    assertFalse(snippet.contains("whale"), snippet);
  }

  @Test
  void missingFileGivesEmptySnippetButTheResultIsStillReturned() throws IOException {
    SearchService service = whaleCorpus();
    Files.delete(dataDir.resolve("moby.txt"));

    List<SearchHit> hits = service.search("whale harpoon", 10);

    assertEquals("moby.txt", hits.get(0).docId());
    assertEquals("", hits.get(0).snippet());
    assertFalse(hits.get(1).snippet().isEmpty(), "other files still get snippets");
  }

  @Test
  void snippetWorksForFilesThatAreNotUtf8() throws IOException {
    Files.write(dataDir.resolve("latin1.txt"), "un café au lait".getBytes(StandardCharsets.ISO_8859_1));
    SearchService service = indexAndCreateService();

    assertEquals("un café au lait", snippetOf(service, "café", "latin1.txt"));
  }

  private static final List<String> QUERIES =
      List.of(
          "whale harpoon",
          "whale",
          "ship sea",
          "harpoon fish",
          "sea ocean ship",
          "captain whale ship",
          "garden",
          "nothing zzzzqqqq");

  private interface SearchBody {
    void run(int threadId) throws Exception;
  }

  private static void runConcurrently(int threads, SearchBody body) throws Exception {
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
              });
      all[t].start();
    }
    assertTrue(ready.await(10, TimeUnit.SECONDS));
    start.countDown();
    for (Thread t : all) {
      t.join();
    }
    if (failure.get() != null) {
      throw new AssertionError("A search thread failed: " + failure.get(), failure.get());
    }
  }

  @Test
  void sixteenParallelSearchersGetTheSameResultsAsOneThread() throws Exception {
    writeWhaleFiles();
    for (int i = 0; i < 300; i++) {
      write("filler/f" + i + ".txt", "filler" + i + " ship text number" + i);
    }
    SearchService big = indexAndCreateService();
    Map<String, List<SearchHit>> expected = new HashMap<>();
    for (String query : QUERIES) {
      expected.put(query, big.search(query, 10));
    }
    assertFalse(expected.get("whale harpoon").isEmpty());

    AtomicInteger checked = new AtomicInteger();
    runConcurrently(
        16,
        id -> {
          for (int round = 0; round < 40; round++) {
            String query = QUERIES.get((id + round) % QUERIES.size());
            List<SearchHit> actual = big.search(query, 10);
            assertEquals(expected.get(query), actual, query);
            checked.incrementAndGet();
          }
        });

    assertEquals(16 * 40, checked.get());
  }

  @Test
  void searchesKeepWorkingWhileTheIndexIsRebuilt() throws Exception {
    write("moby.txt", "whale whale whale harpoon ship sea");
    write("ahab.txt", "whale ship captain");
    write("fish.txt", "harpoon fish");
    IndexManager manager = new IndexManager(new Tokenizer());
    manager.rebuild(dataDir, 2);
    SearchService service = new SearchService(manager, new Tokenizer(), dataDir);
    List<SearchHit> before = service.search("whale harpoon", 10);

    write("big/best.txt", "whale whale whale whale harpoon harpoon harpoon");
    for (int i = 0; i < 2_000; i++) {
      write("big/f" + i + ".txt", "filler" + i + " ship words number" + i + " whale");
    }
    IndexManager reference = new IndexManager(new Tokenizer());
    reference.rebuild(dataDir, 2);
    List<SearchHit> after = new SearchService(reference, new Tokenizer(), dataDir).search("whale harpoon", 10);
    assertFalse(before.equals(after), "test needs two different answers");

    AtomicBoolean rebuilding = new AtomicBoolean(true);
    AtomicInteger searches = new AtomicInteger();
    Thread rebuilder =
        new Thread(
            () -> {
              try {
                manager.rebuild(dataDir, 2);
              } catch (IOException e) {
                throw new IllegalStateException(e);
              } finally {
                rebuilding.set(false);
              }
            });

    rebuilder.start();
    runConcurrently(
        8,
        id -> {
          do {
            List<SearchHit> seen = service.search("whale harpoon", 10);
            assertTrue(seen.equals(before) || seen.equals(after), "mixed or broken result: " + seen);
            searches.incrementAndGet();
          } while (rebuilding.get());
        });
    rebuilder.join();

    assertTrue(searches.get() >= 8);
    assertEquals(after, service.search("whale harpoon", 10), "the new index is used afterwards");
  }
}
