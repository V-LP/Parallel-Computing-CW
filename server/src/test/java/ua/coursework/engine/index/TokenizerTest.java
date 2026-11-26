package ua.coursework.engine.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TokenizerTest {

  private final Tokenizer tokenizer = new Tokenizer();

  @Test
  void lowerCasesAndSplitsOnPunctuation() {
    assertEquals(
        List.of("hello", "world", "2024", "mid"), tokenizer.tokenize("Hello, WORLD! 2024... (mid)"));
  }

  @Test
  void hyphenAndApostropheSeparateTerms() {
    // hyphen and apostrophe split words
    assertEquals(List.of("well", "known"), tokenizer.tokenize("well-known don't"));
  }

  @Test
  void dropsTermsThatAreTooShortOrTooLong() {
    String forty = "a".repeat(40);
    String fortyOne = "a".repeat(41);
    assertEquals(
        List.of("ox", forty), tokenizer.tokenize("x ox " + forty + " " + fortyOne + " 7"));
  }

  @Test
  void removesStopWords() {
    assertEquals(
        List.of("quick", "brown", "fox", "lazy", "dog"),
        tokenizer.tokenize("The quick brown fox AND the lazy dog"));
  }

  @Test
  void keepsNonAsciiLetters() {
    assertEquals(List.of("café", "naïve"), tokenizer.tokenize("Café naïve"));
  }

  @Test
  void emptyAndBlankTextGiveNoTerms() {
    assertEquals(List.of(), tokenizer.tokenize(""));
    assertEquals(List.of(), tokenizer.tokenize("   \n\t  \n"));
    assertEquals(List.of(), tokenizer.tokenize("!!! ... ---"));
  }

  @Test
  void customStopWordsAreUsed() {
    Tokenizer custom = new Tokenizer(Set.of("fox"));
    assertEquals(List.of("the", "quick"), custom.tokenize("the quick fox"));
  }

  @Test
  void skipsGutenbergHeaderAndFooter() {
    String text =
        String.join(
            "\n",
            "The Project Gutenberg EBook of Moby Dick, by Herman Melville",
            "This eBook is for the use of anyone anywhere. License terms follow.",
            "",
            "*** START OF THE PROJECT GUTENBERG EBOOK MOBY DICK ***",
            "",
            "Call me Ishmael.",
            "Some years ago, never mind how long.",
            "",
            "*** END OF THE PROJECT GUTENBERG EBOOK MOBY DICK ***",
            "License: redistribution allowed. Donate to Gutenberg.");

    List<String> terms = tokenizer.tokenize(text);

    assertEquals(List.of("call", "ishmael", "years", "ago", "never", "mind", "long"), terms);
    assertFalse(terms.contains("gutenberg"));
    assertFalse(terms.contains("license"));
    assertFalse(terms.contains("donate"));
  }

  @Test
  void usesWholeTextWhenThereAreNoMarkers() {
    assertEquals(
        List.of("alpha", "beta", "gamma"), tokenizer.tokenize("alpha beta\n\ngamma"));
  }

  @Test
  void onlyStartMarkerSkipsTheHeader() {
    String text = "header words\n*** START OF THIS BOOK ***\nbody words";
    assertEquals(List.of("body", "words"), tokenizer.tokenize(text));
  }

  @Test
  void onlyEndMarkerCutsTheFooter() {
    String text = "body words\n*** END OF THIS BOOK ***\nfooter words";
    assertEquals(List.of("body", "words"), tokenizer.tokenize(text));
  }

  @Test
  void lastStartMarkerInTheHeaderWins() {
    String text =
        String.join(
            "\n",
            "*** START OF THE SMALL PRINT ***",
            "license text",
            "*** START OF THE PROJECT GUTENBERG ETEXT ***",
            "real text");
    assertEquals(List.of("real", "text"), tokenizer.tokenize(text));
  }

  @Test
  void markersAreRecognisedInLowerCaseWithSpaces() {
    String text = "junk\n   *** start of the book ***   \nbody\n *** end of the book ***\njunk";
    assertEquals(List.of("body"), tokenizer.tokenize(text));
  }

  @Test
  void aLineMentioningTheMarkerWithoutStarsIsNotAMarker() {
    assertEquals(
        List.of("start", "text", "end"), tokenizer.tokenize("Start of the text and the end of it"));
  }

  @Test
  void endMarkerFarBelowTheHeaderWindowStillStopsReading() throws IOException {
    StringBuilder text = new StringBuilder();
    int lines = Tokenizer.HEADER_SCAN_LINES * 3;
    for (int i = 0; i < lines; i++) {
      text.append("alpha beta\n");
    }
    text.append("*** END OF THE PROJECT GUTENBERG EBOOK ***\nomega\n");

    List<String> terms = new ArrayList<>();
    tokenizer.tokenize(new BufferedReader(new StringReader(text.toString())), terms::add);

    assertEquals(2 * lines, terms.size());
    assertFalse(terms.contains("omega"));
    assertTrue(terms.contains("alpha"));
  }

  @Test
  void startMarkerBeyondTheHeaderWindowIsIgnored() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < Tokenizer.HEADER_SCAN_LINES + 10; i++) {
      text.append("alpha\n");
    }
    text.append("*** START OF A LATE MARKER ***\nbeta\n");

    List<String> terms = tokenizer.tokenize(text.toString());

    int alphas = Tokenizer.HEADER_SCAN_LINES + 10;
    assertEquals(alphas + 4, terms.size());
    assertTrue(terms.contains("late"));
    assertTrue(terms.contains("beta"));
  }

  @Test
  void forEachBodyLineGivesOnlyTheBodyAndCanStopEarly() throws IOException {
    String text =
        String.join(
            "\n",
            "license header",
            "*** START OF THE PROJECT GUTENBERG EBOOK X ***",
            "line one",
            "line two",
            "line three",
            "*** END OF THE PROJECT GUTENBERG EBOOK X ***",
            "license footer");

    List<String> all = new ArrayList<>();
    tokenizer.forEachBodyLine(
        new BufferedReader(new StringReader(text)),
        line -> {
          all.add(line);
          return true;
        });
    assertEquals(List.of("line one", "line two", "line three"), all);

    List<String> firstTwo = new ArrayList<>();
    tokenizer.forEachBodyLine(
        new BufferedReader(new StringReader(text)),
        line -> {
          firstTwo.add(line);
          return firstTwo.size() < 2;
        });
    assertEquals(List.of("line one", "line two"), firstTwo);
  }
}
