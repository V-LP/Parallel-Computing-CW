package ua.coursework.engine.llm;

@FunctionalInterface
public interface TextGenerator {

  String generate(String prompt) throws OllamaException;
}
