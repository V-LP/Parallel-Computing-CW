package ua.coursework.engine.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.coursework.engine.index.IndexManager;
import ua.coursework.engine.index.IndexSnapshot;
import ua.coursework.engine.index.IndexingInProgressException;
import ua.coursework.engine.llm.OllamaClient;

/** Javalin REST routes. Search and WebSocket are added in later stages. */
public final class ApiServer {

  private static final Logger LOG = LoggerFactory.getLogger(ApiServer.class);
  private static final int MAX_THREADS = 1024;
  private static final ObjectMapper MAPPER =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final OllamaClient ollama;
  private final IndexManager indexManager;
  private final Path dataDir;

  public ApiServer(OllamaClient ollama, IndexManager indexManager, Path dataDir) {
    this.ollama = ollama;
    this.indexManager = indexManager;
    this.dataDir = dataDir;
  }

  /** Builds the Javalin app with all routes but does not start it. */
  public Javalin create() {
    Javalin app = Javalin.create();
    app.get("/api/health", ctx -> ctx.json(new HealthResponse("ok", ollama.isAvailable())));
    app.post("/api/index", this::handleIndex);
    app.get("/api/stats", this::handleStats);
    return app;
  }

  private void handleStats(Context ctx) {
    IndexSnapshot snapshot = indexManager.current();
    ctx.json(
        new StatsResponse(snapshot.documents(), snapshot.buildTimeMs(), snapshot.threadsUsed()));
  }

  private void handleIndex(Context ctx) {
    int threads;
    try {
      threads = parseThreads(ctx.body());
    } catch (IllegalArgumentException e) {
      ctx.status(400).json(ErrorResponse.of(e.getMessage()));
      return;
    }

    try {
      IndexSnapshot snapshot = indexManager.rebuild(dataDir, threads);
      ctx.json(new IndexResponse("ok", snapshot.documents(), snapshot.buildTimeMs()));
    } catch (IndexingInProgressException e) {
      ctx.status(409).json(ErrorResponse.of(e.getMessage()));
    } catch (IOException e) {
      LOG.error("Cannot read data directory {}", dataDir, e);
      ctx.status(500).json(ErrorResponse.of("Cannot read data directory: " + dataDir));
    }
  }

  /** Reads {"threads": N}. An empty body or a missing field means "number of CPUs". */
  private static int parseThreads(String body) {
    if (body == null || body.isBlank()) {
      return Runtime.getRuntime().availableProcessors();
    }
    IndexRequest request;
    try {
      request = MAPPER.readValue(body, IndexRequest.class);
    } catch (IOException e) {
      throw new IllegalArgumentException("Request body must be JSON like {\"threads\": 4}");
    }
    if (request == null || request.threads() == null) {
      return Runtime.getRuntime().availableProcessors();
    }
    int threads = request.threads();
    if (threads < 1 || threads > MAX_THREADS) {
      throw new IllegalArgumentException("threads must be between 1 and " + MAX_THREADS);
    }
    return threads;
  }
}
