package ua.coursework.engine.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import ua.coursework.engine.index.IndexManager;
import ua.coursework.engine.index.Tokenizer;
import ua.coursework.engine.llm.OllamaException;
import ua.coursework.engine.llm.QueryExpander;
import ua.coursework.engine.llm.TextGenerator;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SearchExpansionTest {

  @TempDir Path dataDir;

  private static final Tokenizer TOKENIZER = new Tokenizer();

  private void write(String name, String content) throws IOException {
    Files.writeString(dataDir.resolve(name), content);
  }

  private SearchService service(TextGenerator generator) throws IOException {
    write("moby.txt", "whale whale harpoon ship sea");
    write("ahab.txt", "captain ship harpoon");
    write("fish.txt", "fish market");
    write("garden.txt", "garden flowers rose");
    write("city.txt", "city traffic cars");
    IndexManager manager = new IndexManager(TOKENIZER);
    manager.rebuild(dataDir, 2);
    QueryExpander expander = new QueryExpander(generator, TOKENIZER, 1_000, 0);
    return new SearchService(manager, TOKENIZER, dataDir, expander);
  }

  private static List<String> ids(List<SearchHit> hits) {
    return hits.stream().map(SearchHit::docId).toList();
  }

  @Test
  void expandQueryReturnsOriginalAndExpandedTermsWithoutSearching() throws IOException {
    SearchService service = service(prompt -> "[\"harpoon\", \"captain\", \"whale\"]");

    QueryExpansion expansion = service.expandQuery("The Whale");

    assertEquals("The Whale", expansion.original());
    assertEquals(List.of("whale"), expansion.originalTerms());
    assertEquals(List.of("harpoon", "captain"), expansion.expandedTerms());
  }

  @Test
  void searchExpandedFindsDocumentsThatOnlyContainAnExpandedTerm() throws IOException {
    SearchService service = service(prompt -> "[\"captain\"]");

    List<SearchHit> plain = service.search("whale", 10);
    ExpandedSearch expanded = service.searchWithExpansion("whale", 10);

    assertEquals(List.of("moby.txt"), ids(plain));
    assertEquals(List.of("moby.txt", "ahab.txt"), ids(expanded.hits()));
    assertEquals(List.of("captain"), expanded.expansion().expandedTerms());
  }

  @Test
  void expandedTermsCountLessThanOriginalTerms() throws IOException {
    // "fish" appears once in fish.txt (original word), "captain" once in ahab.txt (expanded word)
    SearchService service = service(prompt -> "[\"captain\"]");

    List<SearchHit> hits = service.searchWithExpansion("fish", 10).hits();
    List<SearchHit> sameWeights = service.keywordSearch(List.of("fish", "captain"), 10);

    assertEquals("fish.txt", hits.get(0).docId());
    assertEquals(1.0, hits.get(0).score());
    assertEquals("ahab.txt", hits.get(1).docId());
    assertTrue(hits.get(1).score() < 1.0);
    // with weight 1.0 for both words the order of equal-ish documents is decided by other factors;
    // with weight 0.5 the expanded word must score strictly lower than with weight 1.0
    double halfWeight = hits.get(1).score();
    double fullWeight =
        sameWeights.stream().filter(h -> h.docId().equals("ahab.txt")).findFirst().get().score();
    assertTrue(halfWeight < fullWeight, halfWeight + " < " + fullWeight);
  }

  @Test
  void aDocumentWithAnOriginalWordBeatsADocumentWithOnlyAnExpandedWord() throws IOException {
    write("a_original.txt", "harpoon something else here today");
    write("b_expanded.txt", "captain something else here today");
    SearchService service = service(prompt -> "[\"captain\"]");

    List<SearchHit> hits = service.searchWithExpansion("harpoon", 10).hits();

    int original = ids(hits).indexOf("a_original.txt");
    int expanded = ids(hits).indexOf("b_expanded.txt");
    assertTrue(original >= 0 && expanded >= 0);
    assertTrue(original < expanded, ids(hits).toString());
  }

  @Test
  void whenTheModelFailsTheSearchEqualsThePlainSearch() throws IOException {
    SearchService service =
        service(
            prompt -> {
              throw new OllamaException("down");
            });

    ExpandedSearch result = service.searchWithExpansion("whale harpoon", 10);

    assertEquals(List.of(), result.expansion().expandedTerms());
    assertEquals(List.of("whale", "harpoon"), result.expansion().originalTerms());
    assertEquals(service.search("whale harpoon", 10), result.hits());
  }

  @Test
  void aServiceWithoutExpanderNeverExpands() throws IOException {
    write("moby.txt", "whale harpoon");
    IndexManager manager = new IndexManager(TOKENIZER);
    manager.rebuild(dataDir, 1);
    SearchService service = new SearchService(manager, TOKENIZER, dataDir);

    ExpandedSearch result = service.searchWithExpansion("whale", 10);

    assertEquals(List.of(), result.expansion().expandedTerms());
    assertEquals(List.of("moby.txt"), ids(result.hits()));
  }

  @Test
  void theTwoStagesCanBeCalledSeparately() throws IOException {
    AtomicInteger calls = new AtomicInteger();
    SearchService service =
        service(
            prompt -> {
              calls.incrementAndGet();
              return "[\"captain\"]";
            });

    QueryExpansion stage1 = service.expandQuery("whale");
    assertEquals(1, calls.get());
    List<SearchHit> stage2 = service.searchExpanded(stage1, 10);
    assertEquals(1, calls.get(), "searching does not ask the model again");

    assertEquals(List.of("moby.txt", "ahab.txt"), ids(stage2));
  }

  @Test
  void aQueryOfStopWordsDoesNotCallTheModel() throws IOException {
    AtomicInteger calls = new AtomicInteger();
    SearchService service =
        service(
            prompt -> {
              calls.incrementAndGet();
              return "[\"captain\"]";
            });

    ExpandedSearch result = service.searchWithExpansion("the and of", 10);

    assertEquals(0, calls.get());
    assertEquals(List.of(), result.hits());
  }

  @Test
  void expandedTermsMissingFromTheIndexDoNotLowerTheScores() throws IOException {
    SearchService service = service(prompt -> "[\"unicorn\", \"dragon\"]");

    assertEquals(service.search("whale", 10), service.searchWithExpansion("whale", 10).hits());
  }
}
