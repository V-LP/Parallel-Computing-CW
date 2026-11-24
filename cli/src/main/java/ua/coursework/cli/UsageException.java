package ua.coursework.cli;

/** The command line is wrong (empty query, unknown option, ...). Leads to exit code 2. */
public final class UsageException extends Exception {

  private static final long serialVersionUID = 1L;

  public UsageException(String message) {
    super(message);
  }
}
