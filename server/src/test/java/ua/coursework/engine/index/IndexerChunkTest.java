package ua.coursework.engine.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class IndexerChunkTest {

  @TempDir Path dataDir;

  private final Tokenizer tokenizer = new Tokenizer();
  /** About 2000 characters per chunk, so small test books give several chunks. */
  private final Chunker small = new Chunker(2_000, 200);

  /** A line with a word that is unique in the whole book, like "uniq0042", plus common words. */
  private static String line(String prefix, int i) {
    return String.format("%s%04d filler text for chunk testing here", prefix, i);
  }

  private static List<String> bookLines(String prefix, int count) {
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      lines.add(line(prefix, i));
    }
    return lines;
  }

  private void write(String name, List<String> lines) throws IOException {
    Path file = dataDir.resolve(name);
    Files.createDirectories(file.getParent());
    Files.writeString(file, String.join("\n", lines) + "\n");
  }

  private ConcurrentInvertedIndex build(Chunker chunker, int threads) throws IOException {
    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex();
    new Indexer(index, tokenizer, chunker).buildIndex(dataDir, threads);
    return index;
  }

  private static Set<String> allPaths(ConcurrentInvertedIndex index) {
    Set<String> paths = new TreeSet<>();
    for (int i = 0; i < index.documentCount(); i++) {
      paths.add(index.documentPath(i));
    }
    return paths;
  }

  // ---------------------------------------------------------------- the same boundaries

  @Test
  void everyLineLandsInTheChunkThatTheChunkerAssignsToIt() throws IOException {
    List<String> lines = bookLines("uniq", 150);
    write("book.txt", lines);

    ConcurrentInvertedIndex index = build(small, 4);

    List<Integer> chunks = ChunkerTest.chunkOfEachLine(small, lines);
    int last = chunks.get(chunks.size() - 1);
    assertTrue(last >= 3, "the test book must have several chunks, has " + last);
    Set<String> expected = new TreeSet<>();
    for (int n = 1; n <= last; n++) {
      expected.add("book.txt#" + n);
    }
    assertEquals(expected, allPaths(index));
    for (int i = 0; i < lines.size(); i++) {
      List<Posting> postings = index.getPostings(String.format("uniq%04d", i));
      assertEquals(1, postings.size());
      assertEquals(
          "book.txt#" + chunks.get(i), index.documentPath(postings.get(0).docId()), "line " + i);
    }
  }

  @Test
  void aThreeHundredFiftyKilobyteBookGivesFourDocumentsNumberedFromOne() throws IOException {
    List<String> lines = new ArrayList<>();
    int lineCount = 350 * 1024 / 57;
    for (int i = 0; i < lineCount; i++) {
      lines.add(String.format("%-56s", "word" + i));
    }
    write("big/book.txt", lines);

    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex();
    int documents = new Indexer(index, tokenizer, Chunker.ofKb(100)).buildIndex(dataDir, 2);

    assertEquals(4, documents);
    assertEquals(
        new TreeSet<>(List.of("big/book.txt#1", "big/book.txt#2", "big/book.txt#3", "big/book.txt#4")),
        allPaths(index));
  }

  @Test
  void aChunkWithoutTermsIsSkippedButTheNumbersOfTheOthersStay() throws IOException {
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      lines.add("alphaword"); // chunk 1
    }
    for (int i = 0; i < 10; i++) {
      lines.add("the the t"); // chunk 2: only stop words and a one-letter word
    }
    for (int i = 0; i < 10; i++) {
      lines.add("gammaword"); // chunk 3
    }
    write("gap.txt", lines);

    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex();
    int documents = new Indexer(index, tokenizer, new Chunker(100, 10)).buildIndex(dataDir, 1);

    assertEquals(2, documents);
    assertEquals(new TreeSet<>(List.of("gap.txt#1", "gap.txt#3")), allPaths(index));
    assertEquals(
        "gap.txt#3", index.documentPath(index.getPostings("gammaword").get(0).docId()));
  }

  @Test
  void documentsAreCountedPerChunkAndTheLengthIsTheNumberOfTermsInTheChunk() throws IOException {
    List<String> lines = bookLines("uniq", 150);
    write("book.txt", lines);
    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex();
    Indexer indexer = new Indexer(index, tokenizer, small);

    int documents = indexer.buildIndex(dataDir, 2);

    assertEquals(index.documentCount(), documents);
    assertEquals(1, indexer.booksIndexed());
    long total = 0;
    for (int i = 0; i < index.documentCount(); i++) {
      total += index.documentLength(i);
    }
    // every line has the same seven terms: uniqNNNN, filler, text, chunk, testing, here (for is a stop word)
    assertEquals(150L * tokenizer.tokenize(lines.get(0)).size(), total);
  }

  @Test
  void aLatin1BookIsChunkedAndIndexedWithTheSameBoundariesAsWhenReadAsLatin1() throws IOException {
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < 120; i++) {
      lines.add(String.format("uniq%04d café naïve filler words in this line", i));
    }
    Files.write(
        dataDir.resolve("latin.txt"),
        (String.join("\n", lines) + "\n").getBytes(StandardCharsets.ISO_8859_1));

    ConcurrentInvertedIndex index = build(small, 2);

    List<Integer> chunks = ChunkerTest.chunkOfEachLine(small, lines);
    assertTrue(chunks.get(chunks.size() - 1) >= 2);
    for (int i = 0; i < lines.size(); i++) {
      List<Posting> postings = index.getPostings(String.format("uniq%04d", i));
      assertEquals(1, postings.size());
      assertEquals(
          "latin.txt#" + chunks.get(i), index.documentPath(postings.get(0).docId()), "line " + i);
    }
    assertEquals(120L, index.getPostings("café").stream().mapToInt(Posting::termFrequency).sum());
  }

  // ---------------------------------------------------------------- threads do not matter

  /** Everything the index knows, with documents named by path (their numbers are random). */
  private static TreeMap<String, Long> fingerprint(ConcurrentInvertedIndex index, Set<String> terms) {
    TreeMap<String, Long> result = new TreeMap<>();
    result.put("#documents", (long) index.documentCount());
    result.put("#terms", (long) index.termCount());
    result.put("#postings", index.postingCount());
    for (int i = 0; i < index.documentCount(); i++) {
      result.put("length|" + index.documentPath(i), (long) index.documentLength(i));
    }
    for (String term : terms) {
      for (Posting posting : index.getPostings(term)) {
        result.put(term + "|" + index.documentPath(posting.docId()), (long) posting.termFrequency());
      }
    }
    return result;
  }

  @Test
  void theResultIsTheSameForOneFourAndEightThreads() throws IOException {
    write("a.txt", bookLines("aaa", 150));
    write("b.txt", bookLines("bbb", 90));
    write("sub/c.txt", bookLines("ccc", 40));
    write("sub/d.txt", bookLines("ddd", 300));
    write("e.txt", bookLines("eee", 12));
    Set<String> terms = new HashSet<>();
    try (var files = Files.walk(dataDir)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        terms.addAll(tokenizer.tokenize(Files.readString(file)));
      }
    }

    TreeMap<String, Long> one = fingerprint(build(small, 1), terms);
    TreeMap<String, Long> four = fingerprint(build(small, 4), terms);
    TreeMap<String, Long> eight = fingerprint(build(small, 8), terms);

    assertTrue(one.get("#documents") > 5, "several chunks expected: " + one.get("#documents"));
    assertEquals(one, four);
    assertEquals(one, eight);
  }

  // ---------------------------------------------------------------- chunking off

  @Test
  void withoutChunkingDocumentIdsAreThePlainPathsAsBefore() throws IOException {
    write("a.txt", bookLines("aaa", 150));
    write("sub/b.txt", bookLines("bbb", 90));

    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex();
    Indexer indexer = new Indexer(index, tokenizer, Chunker.ofKb(0));
    int documents = indexer.buildIndex(dataDir, 4);

    assertEquals(2, documents);
    assertEquals(2, indexer.booksIndexed());
    assertEquals(new TreeSet<>(List.of("a.txt", "sub/b.txt")), allPaths(index));
    assertEquals(
        allPaths(index),
        allPaths(build(Chunker.disabled(), 1)),
        "ofKb(0) and disabled() are the same");
  }

  @Test
  void theTwoArgumentConstructorKeepsTheOldBehaviour() throws IOException {
    write("a.txt", bookLines("aaa", 150));
    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex();
    new Indexer(index, tokenizer).buildIndex(dataDir, 2);
    assertEquals(new TreeSet<>(List.of("a.txt")), allPaths(index));
  }
}
