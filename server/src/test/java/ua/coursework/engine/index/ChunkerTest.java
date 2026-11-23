package ua.coursework.engine.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ChunkerTest {

  @TempDir Path dir;

  /** A line of 9 characters: it counts as 10 with the line break. */
  private static final String LINE = "123456789";

  private static List<String> lines(int count) {
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      lines.add(LINE);
    }
    return lines;
  }

  /** The chunk number of every line, as the chunker sees them. */
  static List<Integer> chunkOfEachLine(Chunker chunker, List<String> lines) {
    List<int[]> seen = new ArrayList<>();
    Chunker.Splitter splitter =
        chunker.newSplitter(
            (chunk, text) -> {
              seen.add(new int[] {chunk});
              return true;
            });
    for (String line : lines) {
      splitter.add(line);
    }
    splitter.finish();
    return seen.stream().map(a -> a[0]).toList();
  }

  private static List<Integer> repeat(int chunk, int times) {
    Integer[] values = new Integer[times];
    Arrays.fill(values, chunk);
    return Arrays.asList(values);
  }

  private static List<Integer> concat(List<Integer>... parts) {
    List<Integer> all = new ArrayList<>();
    for (List<Integer> part : parts) {
      all.addAll(part);
    }
    return all;
  }

  // ---------------------------------------------------------------- boundaries

  @Test
  void withoutChunkingEverythingIsChunkOne() {
    assertEquals(repeat(1, 500), chunkOfEachLine(Chunker.disabled(), lines(500)));
    assertFalse(Chunker.disabled().isEnabled());
  }

  @Test
  @SuppressWarnings("unchecked")
  void aChunkIsClosedAtTheFirstLineThatReachesTheLimit() {
    // 10 characters per line, limit 100: ten lines per chunk, the tenth line closes the chunk
    Chunker chunker = new Chunker(100, 10);
    assertEquals(
        concat(repeat(1, 10), repeat(2, 10), repeat(3, 5)), chunkOfEachLine(chunker, lines(25)));
  }

  @Test
  void theLineThatCrossesTheLimitStaysInTheChunk() {
    // 10 + 10 + 10 = 30 >= 25: the third line closes the chunk and belongs to it
    Chunker chunker = new Chunker(25, 5);
    List<Integer> chunks = chunkOfEachLine(chunker, lines(7));
    assertEquals(List.of(1, 1, 1, 2, 2, 2, 3), chunks);
  }

  @Test
  @SuppressWarnings("unchecked")
  void aShortLastPieceIsAddedToThePreviousChunk() {
    // two full chunks and a tail of two lines (20 characters < 30): the tail joins chunk 2
    Chunker chunker = new Chunker(100, 30);
    assertEquals(concat(repeat(1, 10), repeat(2, 12)), chunkOfEachLine(chunker, lines(22)));
  }

  @Test
  @SuppressWarnings("unchecked")
  void aLastPieceOfExactlyTheMinimumIsAChunkOfItsOwn() {
    Chunker chunker = new Chunker(100, 30);
    assertEquals(
        concat(repeat(1, 10), repeat(2, 10), repeat(3, 3)), chunkOfEachLine(chunker, lines(23)));
  }

  @Test
  void aBookThatEndsExactlyAtABoundaryHasNoEmptyChunk() {
    Chunker chunker = new Chunker(100, 30);
    assertEquals(concat(repeat(1, 10), repeat(2, 10)), chunkOfEachLine(chunker, lines(20)));
  }

  @Test
  void aShortBookIsOneChunkEvenWhenItIsSmallerThanTheMinimum() {
    Chunker chunker = new Chunker(100, 30);
    assertEquals(repeat(1, 3), chunkOfEachLine(chunker, lines(3)));
    assertEquals(List.of(), chunkOfEachLine(chunker, List.of()));
  }

  @Test
  void chunkNumbersOnlyGrowAndStartAtOne() {
    Chunker chunker = new Chunker(300, 50);
    List<Integer> chunks = chunkOfEachLine(chunker, lines(333));
    assertEquals(1, chunks.get(0).intValue());
    for (int i = 1; i < chunks.size(); i++) {
      int step = chunks.get(i) - chunks.get(i - 1);
      assertTrue(step == 0 || step == 1, "line " + i);
    }
  }

  @Test
  void theResultIsAlwaysTheSame() {
    Chunker first = new Chunker(137, 40);
    Chunker second = new Chunker(137, 40);
    List<String> text = new ArrayList<>();
    for (int i = 0; i < 400; i++) {
      text.add("x".repeat(i % 37));
    }
    assertEquals(chunkOfEachLine(first, text), chunkOfEachLine(second, text));
    assertEquals(chunkOfEachLine(first, text), chunkOfEachLine(first, text));
  }

  @Test
  void theGutenbergHeaderAndFooterDoNotCount() throws IOException {
    List<String> text = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      text.add("header line that must not be counted in a chunk");
    }
    text.add("*** START OF THE PROJECT GUTENBERG EBOOK X ***");
    text.addAll(lines(25));
    text.add("*** END OF THE PROJECT GUTENBERG EBOOK X ***");
    text.add("license text after the end");

    List<Integer> chunks = new ArrayList<>();
    new Chunker(100, 10)
        .forEachBodyLine(
            new Tokenizer(),
            new BufferedReader(new StringReader(String.join("\n", text))),
            (chunk, line) -> {
              assertEquals(LINE, line);
              chunks.add(chunk);
              return true;
            });

    assertEquals(chunkOfEachLine(new Chunker(100, 10), lines(25)), chunks);
  }

  @Test
  void readingStopsWhenTheSinkSaysSo() throws IOException {
    List<Integer> seen = new ArrayList<>();
    new Chunker(100, 10)
        .forEachBodyLine(
            new Tokenizer(),
            new BufferedReader(new StringReader(String.join("\n", lines(100)))),
            (chunk, line) -> {
              seen.add(chunk);
              return chunk < 2;
            });
    // the first line of chunk 2 is delivered, then the sink stops the reading
    assertEquals(11, seen.size());
    assertEquals(2, seen.get(10).intValue());
  }

  @Test
  void aThreeHundredFiftyKilobyteBookGivesFourChunksWithGrowingNumbers() {
    Chunker chunker = Chunker.ofKb(100);
    List<String> book = new ArrayList<>();
    int lineCount = 350 * 1024 / 57;
    for (int i = 0; i < lineCount; i++) {
      book.add(String.format("%-56s", "line" + i)); // 56 characters + line break = 57
    }

    List<Integer> chunks = chunkOfEachLine(chunker, book);

    int last = chunks.get(chunks.size() - 1);
    assertTrue(last >= 3 && last <= 4, "chunks: " + last);
    assertEquals(4, last);
    for (int n = 1; n <= last; n++) {
      assertTrue(chunks.contains(n), "chunk " + n + " exists");
    }
  }

  // ---------------------------------------------------------------- settings and ids

  @Test
  void ofKbUsesKilobytesAndOneKilobyteForTheTail() {
    assertEquals(100 * 1024, Chunker.ofKb(100).limitChars());
    assertTrue(Chunker.ofKb(1).isEnabled());
    assertFalse(Chunker.ofKb(0).isEnabled());
    assertThrows(IllegalArgumentException.class, () -> Chunker.ofKb(-1));
    assertThrows(IllegalArgumentException.class, () -> new Chunker(10, 20));
    assertThrows(IllegalArgumentException.class, () -> new Chunker(-1, 0));
  }

  @Test
  void documentIdsHaveTheChunkNumberOnlyWhenChunkingIsOn() {
    assertEquals("a/b.txt#3", Chunker.ofKb(100).docId("a/b.txt", 3));
    assertEquals("a/b.txt", Chunker.disabled().docId("a/b.txt", 1));
  }

  @Test
  void parseDocIdSplitsPathAndChunkNumber() {
    assertEquals(new Chunker.DocRef("a/b.txt", 12), Chunker.parseDocId("a/b.txt#12"));
    assertEquals(new Chunker.DocRef("a/b.txt", 0), Chunker.parseDocId("a/b.txt"));
    assertEquals(new Chunker.DocRef("a#b.txt", 0), Chunker.parseDocId("a#b.txt"));
    assertEquals(new Chunker.DocRef("a#b.txt", 4), Chunker.parseDocId("a#b.txt#4"));
    assertEquals(new Chunker.DocRef("a.txt#", 0), Chunker.parseDocId("a.txt#"));
    assertEquals(new Chunker.DocRef("a.txt#0", 0), Chunker.parseDocId("a.txt#0"));
    assertEquals(new Chunker.DocRef("#5", 0), Chunker.parseDocId("#5"));
    assertEquals(
        new Chunker.DocRef("a.txt#99999999999", 0), Chunker.parseDocId("a.txt#99999999999"));
  }

  // ---------------------------------------------------------------- encoding

  private Path write(String name, byte[] bytes) throws IOException {
    Path file = dir.resolve(name);
    Files.write(file, bytes);
    return file;
  }

  @Test
  void asciiAndValidUtf8FilesAreUtf8() throws IOException {
    assertEquals(
        StandardCharsets.UTF_8,
        BookEncoding.detect(write("a.txt", "plain ascii".getBytes(StandardCharsets.UTF_8))));
    assertEquals(
        StandardCharsets.UTF_8,
        BookEncoding.detect(write("b.txt", "café, привіт, 漢字".getBytes(StandardCharsets.UTF_8))));
    assertEquals(StandardCharsets.UTF_8, BookEncoding.detect(write("empty.txt", new byte[0])));
  }

  @Test
  void aFileWithAnInvalidByteAnywhereIsLatin1() throws IOException {
    byte[] big = new byte[300_000];
    Arrays.fill(big, (byte) 'a');
    big[299_990] = (byte) 0xE9; // a lone Latin-1 "é" far behind the first buffer
    assertEquals(StandardCharsets.ISO_8859_1, BookEncoding.detect(write("late.txt", big)));
    assertEquals(
        StandardCharsets.ISO_8859_1,
        BookEncoding.detect(write("early.txt", new byte[] {(byte) 0xE9, 'a'})));
  }

  @Test
  void aCharacterCutAtTheEndOfTheFileIsLatin1() throws IOException {
    byte[] cut = "abc".getBytes(StandardCharsets.UTF_8);
    byte[] withHalfCharacter = Arrays.copyOf(cut, cut.length + 1);
    withHalfCharacter[cut.length] = (byte) 0xC3; // the first byte of a two-byte character
    assertEquals(
        StandardCharsets.ISO_8859_1, BookEncoding.detect(write("cut.txt", withHalfCharacter)));
  }

  @Test
  void aMultiByteCharacterOnTheBufferBoundaryIsStillUtf8() throws IOException {
    byte[] bytes = new byte[64 * 1024 + 10];
    Arrays.fill(bytes, (byte) 'a');
    byte[] e = "é".getBytes(StandardCharsets.UTF_8); // two bytes: put them across the 64 KB edge
    bytes[64 * 1024 - 1] = e[0];
    bytes[64 * 1024] = e[1];
    assertEquals(StandardCharsets.UTF_8, BookEncoding.detect(write("edge.txt", bytes)));
  }
}
