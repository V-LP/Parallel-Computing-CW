package ua.coursework.engine.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.coursework.engine.concurrent.ThreadPool;
import ua.coursework.engine.index.IndexManager;
import ua.coursework.engine.index.IndexSnapshot;
import ua.coursework.engine.index.IndexingInProgressException;
import ua.coursework.engine.llm.OllamaClient;
import ua.coursework.engine.search.SearchHit;
import ua.coursework.engine.search.SearchService;

public final class ApiServer {

  private static final Logger LOG = LoggerFactory.getLogger(ApiServer.class);
  private static final int MAX_THREADS = 1024;
  private static final String KEYWORD_MODE = "keyword";
  private static final String WEB_DIRECTORY = "/web";

  private static final Duration WS_IDLE_TIMEOUT = Duration.ofMinutes(10);
  private static final ObjectMapper MAPPER =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final OllamaClient ollama;
  private final IndexManager indexManager;
  private final SearchService searchService;
  private final Path dataDir;
  private final ThreadPool searchPool; // null disables websocket

  public ApiServer(
      OllamaClient ollama,
      IndexManager indexManager,
      SearchService searchService,
      Path dataDir) {
    this(ollama, indexManager, searchService, dataDir, null);
  }

  public ApiServer(
      OllamaClient ollama,
      IndexManager indexManager,
      SearchService searchService,
      Path dataDir,
      ThreadPool searchPool) {
    this.ollama = ollama;
    this.indexManager = indexManager;
    this.searchService = searchService;
    this.dataDir = dataDir;
    this.searchPool = searchPool;
  }

  public Javalin create() {
    Javalin app =
        Javalin.create(
            config ->
                config.staticFiles.add(
                    files -> {
                      files.hostedPath = "/";
                      files.directory = WEB_DIRECTORY;
                      files.location = Location.CLASSPATH;
                      // browser always revalidates
                      files.headers = Map.of("Cache-Control", "no-cache");
                    }));
    app.get("/api/health", ctx -> ctx.json(new HealthResponse("ok", ollama.isAvailable())));
    app.post("/api/index", this::handleIndex);
    app.post("/api/search", this::handleSearch);
    app.get("/api/stats", this::handleStats);
    if (searchPool != null) {
      addWebSocket(app);
    }
    return app;
  }

  private void addWebSocket(Javalin app) {
    WsSearchHandler handler = new WsSearchHandler(searchService, searchPool);
    app.ws(
        "/ws/search",
        ws -> {
          ws.onConnect(
              ctx -> {
                ctx.session.setIdleTimeout(WS_IDLE_TIMEOUT);
                handler.onConnect(
                    ctx.session,
                    new WsSearchHandler.Sink() {
                      @Override
                      public void send(String text) {
                        ctx.send(text);
                      }

                      @Override
                      public boolean isOpen() {
                        return ctx.session.isOpen();
                      }
                    });
              });
          ws.onMessage(ctx -> handler.onMessage(ctx.session, ctx.message()));
          ws.onClose(ctx -> handler.onClose(ctx.session));
          ws.onError(ctx -> handler.onClose(ctx.session));
        });
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
