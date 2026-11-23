package ua.coursework.engine.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class IndexerTest {

  @TempDir Path dataDir;

  private final Tokenizer tokenizer = new Tokenizer();

  private void write(String relativePath, String content) throws IOException {
    write(relativePath, content.getBytes(StandardCharsets.UTF_8));
  }

  private void write(String relativePath, byte[] content) throws IOException {
    Path file = dataDir.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.write(file, content);
  }

  private int build(ConcurrentInvertedIndex index, int threads) throws IOException {
    return new Indexer(index, tokenizer).buildIndex(dataDir, threads);
  }

  /** Document path to term frequency, for one term. Independent of the numeric doc ids. */
  private static Map<String, Integer> frequencies(ConcurrentInvertedIndex index, String term) {
    Map<String, Integer> result = new HashMap<>();
    for (Posting p : index.getPostings(term)) {
      result.put(index.documentPath(p.docId()), p.termFrequency());
    }
    return result;
  }

  @Test
  void indexesASmallCorpusExactly() throws IOException {
    write("a.txt", "alpha beta beta gamma");
    write(
        "sub/b.txt",
        String.join(
            "\n",
            "The Project Gutenberg EBook of Something",
            "*** START OF THE PROJECT GUTENBERG EBOOK SOMETHING ***",
            "beta delta delta delta",
            "*** END OF THE PROJECT GUTENBERG EBOOK SOMETHING ***",
            "footer"));
    write("ignored.dat", "alpha alpha alpha");
    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex();

    int indexed = build(index, 1);

    assertEquals(2, indexed);
    assertEquals(2, index.documentCount());
    assertEquals(4, index.termCount()); // alpha, beta, gamma, delta
    assertEquals(5L, index.postingCount()); // a.txt: 3 terms, sub/b.txt: 2 terms
    assertEquals(Map.of("a.txt", 2, "sub/b.txt", 1), frequencies(index, "beta"));
    assertEquals(Map.of("sub/b.txt", 3), frequencies(index, "delta"));
    assertEquals(Map.of("a.txt", 1), frequencies(index, "alpha"));
    assertEquals(Map.of(), frequencies(index, "gutenberg"));
    assertEquals(Map.of(), frequencies(index, "footer"));

    int docA = index.getPostings("gamma").get(0).docId();
    int docB = index.getPostings("delta").get(0).docId();
    assertEquals("a.txt", index.documentPath(docA));
    assertEquals(4, index.documentLength(docA));
    assertEquals("sub/b.txt", index.documentPath(docB));
    assertEquals(4, index.documentLength(docB));
  }

  @Test
  void resultDoesNotDependOnTheNumberOfThreads() throws IOException {
    int files = 150;
    int vocabulary = 300;
    Random random = new Random(42);
    for (int f = 0; f < files; f++) {
      StringBuilder text = new StringBuilder();
      int words = 200 + random.nextInt(600);
      for (int w = 0; w < words; w++) {
        text.append("word").append(random.nextInt(vocabulary)).append(w % 12 == 0 ? "\n" : " ");
      }
      write("books/book" + f + ".txt", text.toString());
    }

    ConcurrentInvertedIndex one = new ConcurrentInvertedIndex();
    assertEquals(files, build(one, 1));

    for (int threads : new int[] {4, 8}) {
      ConcurrentInvertedIndex many = new ConcurrentInvertedIndex();
      assertEquals(files, build(many, threads));

      assertEquals(one.documentCount(), many.documentCount(), "threads=" + threads);
      assertEquals(one.termCount(), many.termCount(), "threads=" + threads);
      assertEquals(one.postingCount(), many.postingCount(), "threads=" + threads);
      // Doc ids may differ between runs, so compare by document path.
      for (int w = 0; w < vocabulary; w++) {
        String term = "word" + w;
        assertEquals(frequencies(one, term), frequencies(many, term), term + " threads=" + threads);
      }
      Map<String, Integer> lengthsOne = lengthsByPath(one);
      assertEquals(lengthsOne, lengthsByPath(many), "threads=" + threads);
      assertEquals(files, lengthsOne.size());
    }
  }

  private static Map<String, Integer> lengthsByPath(ConcurrentInvertedIndex index) {
    Map<String, Integer> lengths = new HashMap<>();
    for (int id = 0; id < index.documentCount(); id++) {
      lengths.put(index.documentPath(id), index.documentLength(id));
    }
    return lengths;
  }

  @Test
  void emptyUnreadableAndFailingFilesDoNotStopIndexing() throws IOException {
    write("good1.txt", "apple banana");
    write("good2.txt", "banana cherry");
    write("good3.txt", "cherry apple apple");
    write("empty.txt", "");
    write("only-stop-words.txt", "the and of to");
    write("bad.txt", "this file will fail with an IOException");
    write("boom.txt", "this file will fail with a RuntimeException");
    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex();

    Indexer indexer =
        new Indexer(index, tokenizer) {
          @Override
          BufferedReader openReader(Path file, Charset charset) throws IOException {
            String name = file.getFileName().toString();
            if (name.equals("bad.txt")) {
              throw new IOException("simulated read error");
            }
            if (name.equals("boom.txt")) {
              throw new IllegalStateException("simulated bug");
            }
            return super.openReader(file, charset);
          }
        };

    int indexed = indexer.buildIndex(dataDir, 4);

    assertEquals(3, indexed);
    assertEquals(3, index.documentCount());
    assertEquals(3, index.termCount());
    assertEquals(Map.of("good1.txt", 1, "good3.txt", 2), frequencies(index, "apple"));
    assertEquals(Map.of(), frequencies(index, "failing"));
  }

  @Test
  void fileThatIsNotUtf8IsReadAsLatin1() throws IOException {
    // 0xE9 is "é" in ISO-8859-1 but an invalid byte sequence in UTF-8.
    byte[] latin1 = "café latte".getBytes(StandardCharsets.ISO_8859_1);
    write("latin1.txt", latin1);
    write("utf8.txt", "naïve latte");
    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex();

    assertEquals(2, build(index, 2));

    assertEquals(Map.of("latin1.txt", 1), frequencies(index, "café"));
    assertEquals(Map.of("utf8.txt", 1), frequencies(index, "naïve"));
    assertEquals(Map.of("latin1.txt", 1, "utf8.txt", 1), frequencies(index, "latte"));
  }

  @Test
  void binaryGarbageDoesNotBreakIndexing() throws IOException {
    byte[] garbage = new byte[50_000];
    new Random(7).nextBytes(garbage);
    write("garbage.txt", garbage);
    write("fine.txt", "reliable words");
    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex();

    int indexed = build(index, 2);

    assertTrue(indexed >= 1);
    assertEquals(Map.of("fine.txt", 1), frequencies(index, "reliable"));
  }

  @Test
  void emptyDirectoryGivesZeroDocuments() throws IOException {
    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex();
    assertEquals(0, build(index, 4));
    assertEquals(0, index.documentCount());
    assertEquals(0, index.termCount());
  }

  @Test
  void missingDirectoryIsReportedAsIoException() {
    Indexer indexer = new Indexer(new ConcurrentInvertedIndex(), tokenizer);
    assertThrows(IOException.class, () -> indexer.buildIndex(dataDir.resolve("nope"), 2));
  }

  @Test
  void invalidThreadCountIsRejected() {
    Indexer indexer = new Indexer(new ConcurrentInvertedIndex(), tokenizer);
    assertThrows(IllegalArgumentException.class, () -> indexer.buildIndex(dataDir, 0));
    assertThrows(IllegalArgumentException.class, () -> indexer.buildIndex(dataDir, -3));
  }

  @Test
  void upperCaseExtensionIsIndexed() throws IOException {
    write("BOOK.TXT", "capital letters");
    ConcurrentInvertedIndex index = new ConcurrentInvertedIndex();
    assertEquals(1, build(index, 1));
    assertEquals("BOOK.TXT", index.documentPath(0));
  }
}
