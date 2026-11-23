package ua.coursework.engine;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.coursework.engine.api.ApiServer;
import ua.coursework.engine.index.IndexManager;
import ua.coursework.engine.index.Tokenizer;
import ua.coursework.engine.llm.OllamaClient;
import ua.coursework.engine.llm.QueryExpander;
import ua.coursework.engine.search.SearchService;

/** Server entry point: reads PORT, OLLAMA_URL and DATA_DIR from the environment and starts Javalin. */
public final class Main {

  private static final Logger LOG = LoggerFactory.getLogger(Main.class);
  private static final int DEFAULT_PORT = 8080;
  private static final String DEFAULT_DATA_DIR = "/data";
  private static final int MODEL_ATTEMPTS = 5;
  private static final long MODEL_PAUSE_MILLIS = 10_000;

  private Main() {}

  public static void main(String[] args) {
    int port = readPort();
    Path dataDir = Path.of(readEnv("DATA_DIR", DEFAULT_DATA_DIR));
    Tokenizer tokenizer = new Tokenizer();
    IndexManager indexManager = new IndexManager(tokenizer);
    OllamaClient ollama = OllamaClient.fromEnv();
    QueryExpander expander = new QueryExpander(ollama, tokenizer);
    SearchService searchService = new SearchService(indexManager, tokenizer, dataDir, expander);

    new ApiServer(ollama, indexManager, searchService, dataDir).create().start(port);
    LOG.info("Server started on port {}, data directory {}", port, dataDir);
    startModelLoader(ollama);
  }

  /**
   * Downloads the LLM model in the background, so the server accepts requests right away. Until the
   * model is ready, query expansion simply falls back to the original query.
   */
  private static void startModelLoader(OllamaClient ollama) {
    Thread loader =
        new Thread(
            () -> ollama.ensureModelAvailableWithRetries(MODEL_ATTEMPTS, MODEL_PAUSE_MILLIS),
            "model-loader");
    loader.setDaemon(true);
    loader.start();
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
