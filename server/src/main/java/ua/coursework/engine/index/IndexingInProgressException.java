package ua.coursework.engine.index;

public class IndexingInProgressException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public IndexingInProgressException() {
    super("Indexing is already in progress");
  }
}
