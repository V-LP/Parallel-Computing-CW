package ua.coursework.engine.llm;

/**
 * Any failure while talking to Ollama: network error, timeout, HTTP error, unreadable answer. It is
 * a checked exception on purpose, so the caller must decide what to do (usually: fall back to the
 * original query).
 */
public class OllamaException extends Exception {

  private static final long serialVersionUID = 1L;

  public OllamaException(String message) {
    super(message);
  }

  public OllamaException(String message, Throwable cause) {
    super(message, cause);
  }
}
