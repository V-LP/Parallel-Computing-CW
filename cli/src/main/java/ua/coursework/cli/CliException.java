package ua.coursework.cli;

public final class CliException extends Exception {

  private static final long serialVersionUID = 1L;

  public CliException(String message) {
    super(message);
  }

  public CliException(String message, Throwable cause) {
    super(message, cause);
  }
}
