package ua.coursework.engine.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.coursework.engine.concurrent.ThreadPool;
import ua.coursework.engine.search.QueryExpansion;
import ua.coursework.engine.search.SearchHit;
import ua.coursework.engine.search.SearchService;

/**
 * Logic of the WebSocket {@code /ws/search}. It does not depend on Javalin: the route in {@link
 * ApiServer} only passes the session, the text of a message and a {@link Sink} to this class.
 *
 * <p>Every incoming message is handled by a worker of our own {@link ThreadPool}, never by the
 * Jetty thread that received it. So a slow Ollama call blocks one worker, not the network threads
 * that serve all the other connections.
 *
 * <p>Order of messages. A connection has its own queue of received texts and at most one worker
 * drains it, so the queries of one connection run one after another. Inside a query the stages run
 * in order in one task. All writes to the session go through one {@code synchronized} block per
 * connection, because the session does not allow two threads to send at the same time.
 */
public final class WsSearchHandler {

  /** Where the messages go. The route wraps the Javalin context in this small interface. */
  public interface Sink {

    /** Sends one text frame. May throw when the client has gone away. */
    void send(String text) throws Exception;

    boolean isOpen();
  }

  /** Queries a connection may have waiting; more are refused with an error message. */
  public static final int MAX_PENDING = 16;

  /** Longest accepted query, in characters. */
  public static final int MAX_QUERY_LENGTH = 1000;

  private static final Logger LOG = LoggerFactory.getLogger(WsSearchHandler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String BAD_MESSAGE = "Message must be JSON like {\"query\": \"text\"}";

  private final SearchService searchService;
  private final ThreadPool pool;
  private final Map<Object, Connection> connections =
      Collections.synchronizedMap(new HashMap<>()); // key = the WebSocket session

  public WsSearchHandler(SearchService searchService, ThreadPool pool) {
    this.searchService = Objects.requireNonNull(searchService, "searchService");
    this.pool = Objects.requireNonNull(pool, "pool");
  }

  /** A client connected. {@code session} identifies the connection in the next calls. */
  public void onConnect(Object session, Sink sink) {
    connections.put(
        Objects.requireNonNull(session, "session"), new Connection(Objects.requireNonNull(sink)));
  }

  /** A text message arrived. Returns at once: the work runs in the pool. */
  public void onMessage(Object session, String text) {
    Connection connection = connections.get(session);
    if (connection == null) {
      LOG.debug("Message from an unknown or closed session ignored");
      return;
    }
    connection.enqueue(text == null ? "" : text);
  }

  /** The client left (or the connection failed). Work that is still queued is dropped. */
  public void onClose(Object session) {
    Connection connection = connections.remove(session);
    if (connection != null) {
      connection.close();
    }
  }

  /** Number of connections that are open now. */
  public int openConnections() {
    return connections.size();
  }

  /** One client. */
  private final class Connection {

    private final Sink sink;
    private final Object sendLock = new Object(); // only one thread writes to the session
    private final Object queueLock = new Object(); // guards pending and draining
    private final ArrayDeque<String> pending = new ArrayDeque<>(); // guarded by queueLock
    private boolean draining; // a worker is taking texts from pending; guarded by queueLock
    private volatile boolean closed;

    Connection(Sink sink) {
      this.sink = sink;
    }

    void close() {
      closed = true;
      synchronized (queueLock) {
        pending.clear();
      }
    }

    void enqueue(String text) {
      boolean startWorker = false;
      boolean overflow = false;
      synchronized (queueLock) {
        if (closed) {
          return;
        }
        if (pending.size() >= MAX_PENDING) {
          overflow = true;
        } else {
          pending.add(text);
          if (!draining) {
            draining = true;
            startWorker = true;
          }
        }
      }
      if (overflow) {
        send(WsMessage.error("Too many queries waiting, wait for the answers"), WsMessage.done());
      } else if (startWorker) {
        submitWorker();
      }
    }

    private void submitWorker() {
      try {
        pool.submit(this::drain);
      } catch (RejectedExecutionException e) { // the pool is shut down: the server is stopping
        synchronized (queueLock) {
          draining = false;
          pending.clear();
        }
        send(WsMessage.error("Server is shutting down"), WsMessage.done());
      }
    }

    /** Runs in a pool worker: handles the waiting texts one by one, in arrival order. */
    private void drain() {
      try {
        while (true) {
          String text;
          synchronized (queueLock) {
            text = closed ? null : pending.poll();
            if (text == null) {
              draining = false;
              return;
            }
          }
          runPipeline(text);
        }
      } catch (RuntimeException | Error e) { // runPipeline catches everything; this is a safety net
        synchronized (queueLock) {
          draining = false;
        }
        throw e;
      }
    }

    /** Validates one message and runs the three stages. Never throws. */
    private void runPipeline(String text) {
      try {
        String query = parseQuery(text);

        // Stage 1: query expansion (may take long: it calls the LLM).
        QueryExpansion expansion = null;
        List<String> expandedTerms = List.of();
        try {
          expansion = searchService.expandQuery(query);
          expandedTerms = expansion.expandedTerms();
        } catch (RuntimeException e) { // expandQuery should not throw; fall back anyway
          LOG.warn("Query expansion failed, searching with the original query", e);
        }
        if (!send(WsMessage.queryExpansion(query, expandedTerms))) {
          return;
        }

        // Stage 2: keyword search. time_ms measures only this stage.
        long start = System.nanoTime();
        List<SearchHit> hits =
            expansion == null
                ? searchService.search(query, SearchService.DEFAULT_TOP_K)
                : searchService.searchExpanded(expansion, SearchService.DEFAULT_TOP_K);
        long timeMs = (System.nanoTime() - start) / 1_000_000;
        List<SearchResult> results =
            hits.stream().map(h -> new SearchResult(h.docId(), h.score(), h.snippet())).toList();
        if (!send(WsMessage.keywordSearch(results, timeMs))) {
          return;
        }

        send(WsMessage.done());
      } catch (BadInputException e) {
        send(WsMessage.error(e.getMessage()), WsMessage.done());
      } catch (RuntimeException e) {
        LOG.error("Search pipeline failed", e);
        send(WsMessage.error("Internal error"), WsMessage.done());
      }
    }

    /**
     * Sends the messages one after another with no message of this connection in between. Returns
     * false when the client is gone (the rest is not sent); this is not an error.
     */
    private boolean send(WsMessage... messages) {
      String[] texts = new String[messages.length];
      for (int i = 0; i < messages.length; i++) {
        try {
          texts[i] = MAPPER.writeValueAsString(messages[i]);
        } catch (JsonProcessingException e) {
          throw new IllegalStateException("Cannot write " + messages[i].stage(), e);
        }
      }
      synchronized (sendLock) {
        for (String text : texts) {
          if (closed || !sink.isOpen()) {
            closed = true;
            return false;
          }
          try {
            sink.send(text);
          } catch (Exception e) { // the client has gone away in the middle of the sending
            LOG.debug("Client is gone, stopping the pipeline: {}", e.toString());
            closed = true;
            return false;
          }
        }
        return true;
      }
    }
  }

  /** Reads {"query": "..."} and returns the query. */
  private static String parseQuery(String text) {
    JsonNode root;
    try {
      root = MAPPER.readTree(text);
    } catch (JsonProcessingException e) {
      throw new BadInputException(BAD_MESSAGE);
    }
    if (root == null || !root.isObject()) {
      throw new BadInputException(BAD_MESSAGE);
    }
    JsonNode query = root.get("query");
    if (query == null || !query.isTextual() || query.asText().isBlank()) {
      throw new BadInputException("query must be a non-empty string");
    }
    if (query.asText().length() > MAX_QUERY_LENGTH) {
      throw new BadInputException("query is longer than " + MAX_QUERY_LENGTH + " characters");
    }
    return query.asText();
  }

  /** The client sent something we cannot use; the message text goes to the client. */
  private static final class BadInputException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    BadInputException(String message) {
      super(message, null, false, false); // no stack trace: this is a normal event
    }
  }
}
