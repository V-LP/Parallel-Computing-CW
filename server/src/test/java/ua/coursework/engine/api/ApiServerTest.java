package ua.coursework.engine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;
import ua.coursework.engine.llm.OllamaClient;

class ApiServerTest {

  /** Port 1 is closed, so Ollama is "unavailable" and the test needs no network. */
  private final ApiServer apiServer = new ApiServer(new OllamaClient("http://localhost:1"));

  @Test
  void healthReturns200WithStatusOk() {
    JavalinTest.test(
        apiServer.create(),
        (server, client) -> {
          var response = client.get("/api/health");
          assertEquals(200, response.code());

          JsonNode json = new ObjectMapper().readTree(response.body().string());
          assertEquals("ok", json.get("status").asText());
          assertTrue(json.has("ollama"));
          assertEquals(false, json.get("ollama").asBoolean());
        });
  }
}
