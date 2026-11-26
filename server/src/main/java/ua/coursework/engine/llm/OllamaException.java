package ua.coursework.engine.llm;

public class OllamaException extends Exception {

  private static final long serialVersionUID = 1L;

  public OllamaException(String message) {
    super(message);
  }

  public OllamaException(String message, Throwable cause) {
    super(message, cause);
  }
}
