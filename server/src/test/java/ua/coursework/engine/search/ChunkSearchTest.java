package ua.coursework.engine.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import ua.coursework.engine.index.Chunker;
import ua.coursework.engine.index.IndexManager;
import ua.coursework.engine.index.Tokenizer;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ChunkSearchTest {

  @TempDir Path dataDir;

  private static final Chunker CHUNKER = new Chunker(2_000, 200);
  private static final int LINES = 150;

  /** Every line has the common word "shared" and a word that is unique in the whole book. */
  private static String line(int i) {
    return String.format("shared uniq%04d filler text for chunk testing here", i);
  }

  private List<String> writeBook() throws IOException {
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < LINES; i++) {
      lines.add(line(i));
    }
    Files.writeString(dataDir.resolve("book.txt"), String.join("\n", lines) + "\n");
    return lines;
  }

  private SearchService service() throws IOException {
    IndexManager manager = new IndexManager(new Tokenizer(), CHUNKER);
    manager.rebuild(dataDir, 2);
    return new SearchService(manager, new Tokenizer(), dataDir);
  }

  /** Index of the first line of the given chunk, found with an independent run of the chunker. */
  private static int firstLineOfChunk(List<String> lines, int chunk) {
    List<Integer> numbers = new ArrayList<>();
    Chunker.Splitter splitter =
        CHUNKER.newSplitter(
            (c, text) -> {
              numbers.add(c);
              return true;
            });
    lines.forEach(splitter::add);
    splitter.finish();
    return numbers.indexOf(chunk);
  }

  @Test
  void searchReturnsDocumentIdsWithTheChunkNumber() throws IOException {
    List<String> lines = writeBook();
    SearchService service = service();
    int first = firstLineOfChunk(lines, 2);

    List<SearchHit> hits = service.search(String.format("uniq%04d", first), 10);

    assertEquals(1, hits.size());
    assertEquals("book.txt#2", hits.get(0).docId());
  }

  @Test
  void aCommonWordFindsEveryChunkAsASeparateDocument() throws IOException {
    List<String> lines = writeBook();
    SearchService service = service();

    List<SearchHit> hits = service.search("shared", 50);

    assertTrue(hits.size() >= 3, "hits: " + hits.size());
    for (SearchHit hit : hits) {
      assertTrue(hit.docId().matches("book\\.txt#\\d+"), hit.docId());
    }
    assertTrue(lines.size() == LINES);
  }

  @Test
  void theSnippetOfChunkTwoComesFromChunkTwoNotFromChunkOne() throws IOException {
    List<String> lines = writeBook();
    SearchService service = service();
    int first = firstLineOfChunk(lines, 2);

    // "shared" is in every line, so the first match in chunk 2 is its first line, and the
    // 200-character window would reach back into chunk 1 if the boundary were not respected
    List<SearchHit> hits = service.search("shared", 50);
    SearchHit chunkTwo = hits.stream().filter(h -> h.docId().equals("book.txt#2")).findFirst().get();

    assertTrue(chunkTwo.snippet().contains(String.format("uniq%04d", first)), chunkTwo.snippet());
    assertFalse(chunkTwo.snippet().contains(String.format("uniq%04d", first - 1)), chunkTwo.snippet());
    assertFalse(chunkTwo.snippet().startsWith("..."), "the snippet starts at the chunk start");

    SearchHit chunkOne = hits.stream().filter(h -> h.docId().equals("book.txt#1")).findFirst().get();
    assertTrue(chunkOne.snippet().contains("uniq0000"), chunkOne.snippet());
    assertFalse(chunkOne.snippet().contains(String.format("uniq%04d", first)));
  }

  @Test
  void theSnippetAtTheEndOfAChunkDoesNotReachIntoTheNextChunk() throws IOException {
    List<String> lines = writeBook();
    SearchService service = service();
    int last = firstLineOfChunk(lines, 2) - 1; // the last line of chunk 1

    List<SearchHit> hits = service.search(String.format("uniq%04d", last), 10);

    assertEquals("book.txt#1", hits.get(0).docId());
    String snippet = hits.get(0).snippet();
    assertTrue(snippet.contains(String.format("uniq%04d", last)), snippet);
    assertFalse(snippet.contains(String.format("uniq%04d", last + 1)), snippet);
    assertFalse(snippet.endsWith("..."), "the snippet ends where the chunk ends: " + snippet);
  }

  @Test
  void aDocumentIdWithoutAChunkNumberStillGivesASnippetFromTheWholeFile() throws IOException {
    writeBook();
    IndexManager manager = new IndexManager(new Tokenizer(), CHUNKER);
    SnippetExtractor extractor = new SnippetExtractor(new Tokenizer(), dataDir, manager.chunker());

    String snippet = extractor.extract("book.txt", List.of("uniq0100"));

    assertTrue(snippet.contains("uniq0100"), snippet);
  }

  @Test
  void aMissingChunkNumberOrFileGivesAnEmptySnippet() throws IOException {
    writeBook();
    IndexManager manager = new IndexManager(new Tokenizer(), CHUNKER);
    SnippetExtractor extractor = new SnippetExtractor(new Tokenizer(), dataDir, manager.chunker());

    assertEquals("", extractor.extract("book.txt#99", List.of("uniq0001")));
    assertEquals("", extractor.extract("nothing.txt#1", List.of("uniq0001")));
  }

  @Test
  void withChunkingOffSearchReturnsPlainPaths() throws IOException {
    writeBook();
    IndexManager manager = new IndexManager(new Tokenizer(), Chunker.ofKb(0));
    manager.rebuild(dataDir, 2);
    SearchService service = new SearchService(manager, new Tokenizer(), dataDir);

    List<SearchHit> hits = service.search("uniq0100", 10);

    assertEquals(List.of("book.txt"), hits.stream().map(SearchHit::docId).toList());
    assertTrue(hits.get(0).snippet().contains("uniq0100"));
  }

  @Test
  void aLatin1BookGivesTheSameChunksAndWorkingSnippets() throws IOException {
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < LINES; i++) {
      lines.add(String.format("shared uniq%04d caf\u00e9 na\u00efve filler text here", i));
    }
    Files.write(
        dataDir.resolve("book.txt"),
        (String.join("\n", lines) + "\n").getBytes(StandardCharsets.ISO_8859_1));
    SearchService service = service();
    int first = firstLineOfChunk(lines, 2);

    List<SearchHit> hits = service.search(String.format("uniq%04d", first), 10);

    assertEquals("book.txt#2", hits.get(0).docId());
    assertTrue(hits.get(0).snippet().contains(String.format("uniq%04d", first)), hits.get(0).snippet());
    assertTrue(hits.get(0).snippet().contains("caf\u00e9"));
  }
}
