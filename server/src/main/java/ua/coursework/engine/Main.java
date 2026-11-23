package ua.coursework.engine;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.coursework.engine.api.ApiServer;
import ua.coursework.engine.index.IndexManager;
import ua.coursework.engine.index.Tokenizer;
import ua.coursework.engine.llm.OllamaClient;

/** Server entry point: reads PORT, OLLAMA_URL and DATA_DIR from the environment and starts Javalin. */
public final class Main {

  private static final Logger LOG = LoggerFactory.getLogger(Main.class);
  private static final int DEFAULT_PORT = 8080;
  private static final String DEFAULT_DATA_DIR = "/data";

  private Main() {}

  public static void main(String[] args) {
    int port = readPort();
    Path dataDir = Path.of(readEnv("DATA_DIR", DEFAULT_DATA_DIR));
    IndexManager indexManager = new IndexManager(new Tokenizer());

    new ApiServer(OllamaClient.fromEnv(), indexManager, dataDir).create().start(port);
    LOG.info("Server started on port {}, data directory {}", port, dataDir);
  }

  private static String readEnv(String name, String defaultValue) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value.trim();
  }

  private static int readPort() {
    String value = readEnv("PORT", String.valueOf(DEFAULT_PORT));
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      LOG.warn("Invalid PORT '{}', using {}", value, DEFAULT_PORT);
      return DEFAULT_PORT;
    }
  }
}
