package ua.coursework.engine;

import io.javalin.Javalin;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.coursework.engine.api.ApiServer;
import ua.coursework.engine.concurrent.ThreadPool;
import ua.coursework.engine.index.Chunker;
import ua.coursework.engine.index.IndexManager;
import ua.coursework.engine.index.Tokenizer;
import ua.coursework.engine.llm.OllamaClient;
import ua.coursework.engine.llm.QueryExpander;
import ua.coursework.engine.search.SearchService;

/**
 * Server entry point: reads PORT, OLLAMA_URL, DATA_DIR and SEARCH_THREADS from the environment and
 * starts Javalin.
 */
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
    Chunker chunker = Chunker.fromEnv();
    IndexManager indexManager = new IndexManager(tokenizer, chunker);
    OllamaClient ollama = OllamaClient.fromEnv();
    QueryExpander expander = new QueryExpander(ollama, tokenizer);
    SearchService searchService = new SearchService(indexManager, tokenizer, dataDir, expander);

    int searchThreads = readSearchThreads();
    ThreadPool searchPool = new ThreadPool(searchThreads);
    Javalin app =
        new ApiServer(ollama, indexManager, searchService, dataDir, searchPool)
            .create()
            .start(port);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  app.stop();
                  searchPool.shutdown();
                },
                "shutdown"));
    LOG.info(
        "Server started on port {}, data directory {}, WebSocket search threads {}",
        port,
        dataDir,
        searchThreads);
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

  /** Size of the pool that runs WebSocket queries: SEARCH_THREADS, default = number of CPUs. */
  private static int readSearchThreads() {
    int cpus = Runtime.getRuntime().availableProcessors();
    String value = readEnv("SEARCH_THREADS", String.valueOf(cpus));
    try {
      int threads = Integer.parseInt(value);
      if (threads >= 1) {
        return threads;
      }
    } catch (NumberFormatException e) {
      // falls through to the warning below
    }
    LOG.warn("Invalid SEARCH_THREADS '{}', using {}", value, cpus);
    return cpus;
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
