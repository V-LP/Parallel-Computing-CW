package ua.coursework.cli;

public record CliOptions(
    Command command,
    String query,
    String url,
    Integer timeoutSeconds,
    boolean rest,
    boolean json,
    Integer indexThreads) {

  public enum Command {
    SEARCH,
    HEALTH,
    STATS,
    INDEX,
    HELP
  }
}
