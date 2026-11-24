package ua.coursework.cli;

/**
 * Parsed command line.
 *
 * @param command what to do
 * @param query the search query (only for {@link Command#SEARCH}); "-" means "read it from stdin"
 * @param url value of --url, or null
 * @param timeoutSeconds value of --timeout, or null (every command has its own default)
 * @param rest use POST /api/search instead of the WebSocket
 * @param json print the raw JSON of the server instead of formatted text
 * @param indexThreads value after --index, or null
 */
public record CliOptions(
    Command command,
    String query,
    String url,
    Integer timeoutSeconds,
    boolean rest,
    boolean json,
    Integer indexThreads) {

  /** What the user asked for. */
  public enum Command {
    SEARCH,
    HEALTH,
    STATS,
    INDEX,
    HELP
  }
}
