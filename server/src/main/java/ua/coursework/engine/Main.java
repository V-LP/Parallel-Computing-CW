package ua.coursework.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.coursework.engine.api.ApiServer;
import ua.coursework.engine.llm.OllamaClient;

/** Server entry point: reads PORT and OLLAMA_URL from the environment and starts Javalin. */
public final class Main {

  private static final Logger LOG = LoggerFactory.getLogger(Main.class);
  private static final int DEFAULT_PORT = 8080;

  private Main() {}

  public static void main(String[] args) {
    int port = readPort();
    new ApiServer(OllamaClient.fromEnv()).create().start(port);
    LOG.info("Server started on port {}", port);
  }

  private static int readPort() {
    String value = System.getenv("PORT");
    if (value == null || value.isBlank()) {
      return DEFAULT_PORT;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      LOG.warn("Invalid PORT '{}', using {}", value, DEFAULT_PORT);
      return DEFAULT_PORT;
    }
  }
}
