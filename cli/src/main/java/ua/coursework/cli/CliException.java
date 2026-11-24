package ua.coursework.cli;

/** A runtime failure (server down, HTTP error, timeout). The message is shown to the user. */
public final class CliException extends Exception {

  private static final long serialVersionUID = 1L;

  public CliException(String message) {
    super(message);
  }

  public CliException(String message, Throwable cause) {
    super(message, cause);
  }
}
