package ua.coursework.engine.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.coursework.engine.index.IndexManager;
import ua.coursework.engine.index.IndexSnapshot;
import ua.coursework.engine.index.IndexingInProgressException;
import ua.coursework.engine.llm.OllamaClient;
import ua.coursework.engine.search.SearchHit;
import ua.coursework.engine.search.SearchService;

/** Javalin REST routes. The WebSocket is added in a later stage. */
public final class ApiServer {

  private static final Logger LOG = LoggerFactory.getLogger(ApiServer.class);
  private static final int MAX_THREADS = 1024;
  private static final String KEYWORD_MODE = "keyword";
  private static final ObjectMapper MAPPER =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final OllamaClient ollama;
  private final IndexManager indexManager;
  private final SearchService searchService;
  private final Path dataDir;

  public ApiServer(
      OllamaClient ollama,
      IndexManager indexManager,
      SearchService searchService,
      Path dataDir) {
    this.ollama = ollama;
    this.indexManager = indexManager;
    this.searchService = searchService;
    this.dataDir = dataDir;
  }

  /** Builds the Javalin app with all routes but does not start it. */
  public Javalin create() {
    Javalin app = Javalin.create();
    app.get("/api/health", ctx -> ctx.json(new HealthResponse("ok", ollama.isAvailable())));
    app.post("/api/index", this::handleIndex);
    app.post("/api/search", this::handleSearch);
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

  private void handleSearch(Context ctx) {
    SearchRequest request;
    try {
      request = parseSearchRequest(ctx.body());
    } catch (IllegalArgumentException e) {
      ctx.status(400).json(ErrorResponse.of(e.getMessage()));
      return;
    }

    long start = System.nanoTime();
    List<SearchHit> hits = searchService.search(request.query(), SearchService.DEFAULT_TOP_K);
    long timeMs = (System.nanoTime() - start) / 1_000_000;

    List<SearchResult> results =
        hits.stream().map(h -> new SearchResult(h.docId(), h.score(), h.snippet())).toList();
    ctx.json(new SearchResponse(results, timeMs));
  }

  /** Reads {"query": "...", "mode": "keyword"}. A missing mode means "keyword". */
  private static SearchRequest parseSearchRequest(String body) {
    if (body == null || body.isBlank()) {
      throw new IllegalArgumentException(
          "Request body must be JSON like {\"query\": \"text\", \"mode\": \"keyword\"}");
    }
    SearchRequest request;
    try {
      request = MAPPER.readValue(body, SearchRequest.class);
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Request body must be JSON like {\"query\": \"text\", \"mode\": \"keyword\"}");
    }
    if (request == null || request.query() == null || request.query().isBlank()) {
      throw new IllegalArgumentException("query must not be empty");
    }
    if (request.mode() != null && !KEYWORD_MODE.equalsIgnoreCase(request.mode().trim())) {
      throw new IllegalArgumentException("mode must be \"" + KEYWORD_MODE + "\"");
    }
    return request;
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
