package ua.coursework.engine.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class OllamaClientTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String MODEL = "qwen2.5:1.5b";

  private static OllamaClient client(FakeOllama fake, Duration generateTimeout) {
    return new OllamaClient(fake.url(), MODEL, generateTimeout, Duration.ofSeconds(10));
  }

  // ---------------------------------------------------------------- isAvailable

  @Test
  void isAvailableReturnsFalseWithoutThrowingWhenUnreachable() {
    assertFalse(new OllamaClient("http://localhost:1").isAvailable());
  }

  @Test
  void isAvailableReturnsFalseForMalformedUrl() {
    assertFalse(new OllamaClient("not a url").isAvailable());
  }

  @Test
  void isAvailableIsTrueWhenTheServiceAnswers() throws Exception {
    try (FakeOllama fake = new FakeOllama().on("/api/tags", 200, "{\"models\":[]}").start()) {
      assertTrue(client(fake, Duration.ofSeconds(5)).isAvailable());
    }
  }

  // ---------------------------------------------------------------- generate

  @Test
  void generateReturnsTheResponseField() throws Exception {
    try (FakeOllama fake =
        new FakeOllama()
            .on("/api/generate", 200, "{\"model\":\"x\",\"response\":\"[\\\"a\\\",\\\"b\\\"]\",\"done\":true}")
            .start()) {
      assertEquals("[\"a\",\"b\"]", client(fake, Duration.ofSeconds(5)).generate("hello"));
    }
  }

  @Test
  void generateSendsTheDocumentedRequestBody() throws Exception {
    try (FakeOllama fake =
        new FakeOllama().on("/api/generate", 200, "{\"response\":\"ok\"}").start()) {
      client(fake, Duration.ofSeconds(5)).generate("my prompt");

      JsonNode body = JSON.readTree(fake.lastBody("/api/generate"));
      assertEquals(MODEL, body.get("model").asText());
      assertEquals("my prompt", body.get("prompt").asText());
      assertTrue(body.get("stream").isBoolean() && !body.get("stream").asBoolean());
      assertEquals(0.2, body.get("options").get("temperature").asDouble());
      assertEquals(120, body.get("options").get("num_predict").asInt());
      assertEquals("30m", body.get("keep_alive").asText());
    }
  }

  @Test
  void generateThrowsOllamaExceptionWhenTheAnswerIsSlowerThanTheTimeout() throws Exception {
    try (FakeOllama fake =
        new FakeOllama()
            .on(
                "/api/generate",
                (body, responder) -> {
                  Thread.sleep(2_000);
                  responder.send(200, "{\"response\":\"late\"}");
                })
            .start()) {
      long start = System.nanoTime();
      assertThrows(
          OllamaException.class, () -> client(fake, Duration.ofMillis(300)).generate("hello"));
      long millis = (System.nanoTime() - start) / 1_000_000;
      assertTrue(millis < 1_800, "must give up at the timeout, not wait for the answer: " + millis);
    }
  }

  @Test
  void generateThrowsOllamaExceptionOnHttp500() throws Exception {
    try (FakeOllama fake =
        new FakeOllama().on("/api/generate", 500, "{\"error\":\"model crashed\"}").start()) {
      OllamaException e =
          assertThrows(
              OllamaException.class, () -> client(fake, Duration.ofSeconds(5)).generate("hello"));
      assertTrue(e.getMessage().contains("500"), e.getMessage());
    }
  }

  @Test
  void generateThrowsOllamaExceptionWhenTheAnswerIsNotJson() throws Exception {
    try (FakeOllama fake =
        new FakeOllama().on("/api/generate", 200, "<html>proxy error</html>").start()) {
      assertThrows(
          OllamaException.class, () -> client(fake, Duration.ofSeconds(5)).generate("hello"));
    }
  }

  @Test
  void generateThrowsOllamaExceptionWhenResponseFieldIsMissingOrNotText() throws Exception {
    try (FakeOllama fake = new FakeOllama().on("/api/generate", 200, "{\"done\":true}").start()) {
      assertThrows(
          OllamaException.class, () -> client(fake, Duration.ofSeconds(5)).generate("hello"));
    }
    try (FakeOllama fake =
        new FakeOllama().on("/api/generate", 200, "{\"response\":[1,2]}").start()) {
      assertThrows(
          OllamaException.class, () -> client(fake, Duration.ofSeconds(5)).generate("hello"));
    }
  }

  @Test
  void generateThrowsOllamaExceptionWhenUnreachableOrUrlIsMalformed() {
    assertThrows(
        OllamaException.class,
        () ->
            new OllamaClient("http://localhost:1", MODEL, Duration.ofSeconds(2), Duration.ofSeconds(2))
                .generate("hello"));
    assertThrows(OllamaException.class, () -> new OllamaClient("not a url").generate("hello"));
  }

  // ---------------------------------------------------------------- ensureModelAvailable

  @Test
  void ensureModelAvailableDoesNotDownloadWhenTheModelIsPresent() throws Exception {
    try (FakeOllama fake =
        new FakeOllama()
            .on("/api/tags", 200, "{\"models\":[{\"name\":\"other:7b\"},{\"name\":\"qwen2.5:1.5b\"}]}")
            .on("/api/pull", 200, "{\"status\":\"success\"}")
            .start()) {
      client(fake, Duration.ofSeconds(5)).ensureModelAvailable();

      assertEquals(1, fake.count("/api/tags"));
      assertEquals(0, fake.count("/api/pull"));
    }
  }

  @Test
  void ensureModelAvailableDownloadsWhenTheModelIsMissing() throws Exception {
    try (FakeOllama fake =
        new FakeOllama()
            .on("/api/tags", 200, "{\"models\":[{\"name\":\"other:7b\"}]}")
            .on("/api/pull", 200, "{\"status\":\"success\"}")
            .start()) {
      client(fake, Duration.ofSeconds(5)).ensureModelAvailable();

      assertEquals(1, fake.count("/api/pull"));
      JsonNode body = JSON.readTree(fake.lastBody("/api/pull"));
      assertEquals(MODEL, body.get("model").asText());
      assertEquals(MODEL, body.get("name").asText());
      assertFalse(body.get("stream").asBoolean());
    }
  }

  @Test
  void ensureModelAvailableDownloadsWhenTheTagsListIsEmpty() throws Exception {
    try (FakeOllama fake =
        new FakeOllama()
            .on("/api/tags", 200, "{\"models\":[]}")
            .on("/api/pull", 200, "{\"status\":\"success\"}")
            .start()) {
      client(fake, Duration.ofSeconds(5)).ensureModelAvailable();
      assertEquals(1, fake.count("/api/pull"));
    }
  }

  @Test
  void aModelNameWithoutTagMeansLatest() throws Exception {
    try (FakeOllama fake =
        new FakeOllama()
            .on("/api/tags", 200, "{\"models\":[{\"name\":\"llama3:latest\"}]}")
            .on("/api/pull", 200, "{\"status\":\"success\"}")
            .start()) {
      new OllamaClient(fake.url(), "llama3", Duration.ofSeconds(5), Duration.ofSeconds(5))
          .ensureModelAvailable();
      assertEquals(0, fake.count("/api/pull"));
    }
  }

  @Test
  void ensureModelAvailableThrowsWhenTheDownloadFails() throws Exception {
    try (FakeOllama fake =
        new FakeOllama()
            .on("/api/tags", 200, "{\"models\":[]}")
            .on("/api/pull", 500, "{\"error\":\"disk full\"}")
            .start()) {
      assertThrows(
          OllamaException.class, () -> client(fake, Duration.ofSeconds(5)).ensureModelAvailable());
    }
    try (FakeOllama fake =
        new FakeOllama()
            .on("/api/tags", 200, "{\"models\":[]}")
            .on("/api/pull", 200, "{\"error\":\"pull model manifest: not found\"}")
            .start()) {
      assertThrows(
          OllamaException.class, () -> client(fake, Duration.ofSeconds(5)).ensureModelAvailable());
    }
  }

  @Test
  void ensureModelAvailableThrowsWhenOllamaIsDown() {
    OllamaClient client =
        new OllamaClient("http://localhost:1", MODEL, Duration.ofSeconds(2), Duration.ofSeconds(2));
    assertThrows(OllamaException.class, client::ensureModelAvailable);
  }

  // ---------------------------------------------------------------- retries

  @Test
  void retriesUntilOllamaStartsAnswering() throws Exception {
    AtomicInteger tagCalls = new AtomicInteger();
    try (FakeOllama fake =
        new FakeOllama()
            .on(
                "/api/tags",
                (body, responder) -> {
                  if (tagCalls.incrementAndGet() < 3) {
                    responder.send(503, "starting");
                  } else {
                    responder.send(200, "{\"models\":[{\"name\":\"qwen2.5:1.5b\"}]}");
                  }
                })
            .start()) {
      assertTrue(client(fake, Duration.ofSeconds(5)).ensureModelAvailableWithRetries(5, 10));
      assertEquals(3, tagCalls.get());
    }
  }

  @Test
  void retriesStopAfterTheGivenNumberOfAttempts() throws Exception {
    try (FakeOllama fake = new FakeOllama().on("/api/tags", 503, "down").start()) {
      assertFalse(client(fake, Duration.ofSeconds(5)).ensureModelAvailableWithRetries(4, 10));
      assertEquals(4, fake.count("/api/tags"));
    }
  }

  @Test
  void aInterruptedRetrySleepStopsTheLoopAndKeepsTheInterruptFlag() throws Exception {
    try (FakeOllama fake = new FakeOllama().on("/api/tags", 503, "down").start()) {
      OllamaClient client = client(fake, Duration.ofSeconds(5));
      boolean[] result = {true};
      boolean[] flag = {false};
      Thread thread =
          new Thread(
              () -> {
                result[0] = client.ensureModelAvailableWithRetries(5, 60_000);
                flag[0] = Thread.currentThread().isInterrupted();
              });
      thread.start();
      while (fake.count("/api/tags") == 0) {
        Thread.sleep(10);
      }
      thread.interrupt();
      thread.join(10_000);

      assertFalse(thread.isAlive());
      assertFalse(result[0]);
      assertTrue(flag[0]);
    }
  }
}
