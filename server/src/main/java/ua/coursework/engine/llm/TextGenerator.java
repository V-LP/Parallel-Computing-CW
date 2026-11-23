package ua.coursework.engine.llm;

/** Something that turns a prompt into text. {@link OllamaClient} is the real one; tests use fakes. */
@FunctionalInterface
public interface TextGenerator {

  /**
   * Returns the generated text.
   *
   * @throws OllamaException if the text could not be produced
   */
  String generate(String prompt) throws OllamaException;
}
