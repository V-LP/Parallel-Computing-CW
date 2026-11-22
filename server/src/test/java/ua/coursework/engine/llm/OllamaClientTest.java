package ua.coursework.engine.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class OllamaClientTest {

  @Test
  void isAvailableReturnsFalseWithoutThrowingWhenUnreachable() {
    assertFalse(new OllamaClient("http://localhost:1").isAvailable());
  }

  @Test
  void isAvailableReturnsFalseForMalformedUrl() {
    assertFalse(new OllamaClient("not a url").isAvailable());
  }
}
