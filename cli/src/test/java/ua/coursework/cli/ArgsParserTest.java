package ua.coursework.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import ua.coursework.cli.CliOptions.Command;

class ArgsParserTest {

  private static CliOptions parse(String... args) throws UsageException {
    return ArgsParser.parse(args);
  }

  private static void assertUsageError(String... args) {
    assertThrows(UsageException.class, () -> ArgsParser.parse(args));
  }

  @Test
  void aQuotedQueryIsOneArgument() throws Exception {
    CliOptions options = parse("white whale");
    assertEquals(Command.SEARCH, options.command());
    assertEquals("white whale", options.query());
    assertFalse(options.rest());
    assertFalse(options.json());
    assertNull(options.url());
    assertNull(options.timeoutSeconds());
  }

  @Test
  void unquotedWordsAreJoinedIntoTheQuery() throws Exception {
    assertEquals("white whale", parse("white", "whale").query());
  }

  @Test
  void cyrillicQueryIsKeptAsIs() throws Exception {
    assertEquals("біла вага", parse("біла вага").query());
  }

  @Test
  void optionsCanStandBeforeOrAfterTheQuery() throws Exception {
    CliOptions options = parse("--rest", "white", "--json", "whale", "--timeout", "5");
    assertEquals("white whale", options.query());
    assertTrue(options.rest());
    assertTrue(options.json());
    assertEquals(Integer.valueOf(5), options.timeoutSeconds());
  }

  @Test
  void urlAndTimeoutAcceptBothForms() throws Exception {
    CliOptions a = parse("q", "--url", "http://h:1", "--timeout", "7");
    CliOptions b = parse("q", "--url=http://h:1", "--timeout=7");
    assertEquals("http://h:1", a.url());
    assertEquals("http://h:1", b.url());
    assertEquals(Integer.valueOf(7), a.timeoutSeconds());
    assertEquals(Integer.valueOf(7), b.timeoutSeconds());
  }

  @Test
  void healthAndStatsTakeNoQuery() throws Exception {
    assertEquals(Command.HEALTH, parse("--health").command());
    assertEquals(Command.STATS, parse("--stats", "--json").command());
    assertUsageError("--health", "white");
    assertUsageError("--stats", "white");
  }

  @Test
  void indexHasAnOptionalNumberOfThreads() throws Exception {
    CliOptions plain = parse("--index");
    assertEquals(Command.INDEX, plain.command());
    assertNull(plain.indexThreads());
    assertEquals(Integer.valueOf(4), parse("--index", "4").indexThreads());
    assertEquals(Integer.valueOf(8), parse("--index=8").indexThreads());
    CliOptions withOption = parse("--index", "--json");
    assertNull(withOption.indexThreads());
    assertTrue(withOption.json());
    assertUsageError("--index", "abc");
    assertUsageError("--index", "0");
    assertUsageError("--index", "-3");
  }

  @Test
  void twoCommandsAtOnceAreRejected() {
    assertUsageError("--health", "--stats");
    assertUsageError("--index", "--health");
  }

  @Test
  void emptyOrBlankQueryIsRejected() {
    assertUsageError();
    assertUsageError("");
    assertUsageError("   ");
    assertUsageError("--rest");
  }

  @Test
  void unknownOptionIsRejected() {
    assertUsageError("--bogus", "q");
    assertUsageError("q", "--Rest");
  }

  @Test
  void optionsWithMissingOrBadValuesAreRejected() {
    assertUsageError("q", "--timeout");
    assertUsageError("q", "--timeout", "abc");
    assertUsageError("q", "--timeout", "0");
    assertUsageError("q", "--timeout", "999999999");
    assertUsageError("q", "--url");
  }

  @Test
  void helpWinsOverEverythingElse() throws Exception {
    assertEquals(Command.HELP, parse("--help").command());
    assertEquals(Command.HELP, parse("-h").command());
    assertEquals(Command.HELP, parse("--bogus", "--help").command());
    assertEquals(Command.HELP, parse("--health", "--stats", "--help").command());
  }

  @Test
  void doubleDashEndsTheOptions() throws Exception {
    assertEquals("--weird query", parse("--", "--weird", "query").query());
  }

  @Test
  void aSingleDashIsTheStdinMarker() throws Exception {
    assertEquals("-", parse("-").query());
  }
}
