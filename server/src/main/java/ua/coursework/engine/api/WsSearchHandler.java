package ua.coursework.engine.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
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

public final class WsSearchHandler {

  public interface Sink {

    void send(String text) throws Exception;

    boolean isOpen();
  }

  public static final int MAX_PENDING = 16;

  public static final int MAX_QUERY_LENGTH = 1000;

  private static final Logger LOG = LoggerFactory.getLogger(WsSearchHandler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String BAD_MESSAGE = "Message must be JSON like {\"query\": \"text\"}";

  private final SearchService searchService;
  private final ThreadPool pool;
  private final Map<Object, Connection> connections = new HashMap<>();

  public WsSearchHandler(SearchService searchService, ThreadPool pool) {
    this.searchService = Objects.requireNonNull(searchService, "searchService");
    this.pool = Objects.requireNonNull(pool, "pool");
  }

  public void onConnect(Object session, Sink sink) {
    Objects.requireNonNull(session, "session");
    Connection connection = new Connection(Objects.requireNonNull(sink));
    synchronized (connections) {
      connections.put(session, connection);
    }
  }

  public void onMessage(Object session, String text) {
    Connection connection;
    synchronized (connections) {
      connection = connections.get(session);
    }
    if (connection == null) {
      LOG.debug("Message from an unknown or closed session ignored");
      return;
    }
    connection.enqueue(text == null ? "" : text);
  }

  public void onClose(Object session) {
    Connection connection;
    synchronized (connections) {
      connection = connections.remove(session);
    }
    if (connection != null) {
      connection.close();
    }
  }

  public int openConnections() {
    synchronized (connections) {
      return connections.size();
    }
  }

  private final class Connection {

    private final Sink sink;
    private final Object sendLock = new Object(); // single writer per session
    private final Object queueLock = new Object();
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private boolean draining; // a worker is draining pending
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
      } catch (RejectedExecutionException e) {
        synchronized (queueLock) {
          draining = false;
          pending.clear();
        }
        send(WsMessage.error("Server is shutting down"), WsMessage.done());
      }
    }

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
      } catch (RuntimeException | Error e) {
        synchronized (queueLock) {
          draining = false;
        }
        throw e;
      }
    }

    private void runPipeline(String text) {
      try {
        String query = parseQuery(text);

        QueryExpansion expansion = null;
        List<String> expandedTerms = List.of();
        try {
          expansion = searchService.expandQuery(query);
          expandedTerms = expansion.expandedTerms();
        } catch (RuntimeException e) {
          LOG.warn("Query expansion failed, searching with the original query", e);
        }
        if (!send(WsMessage.queryExpansion(query, expandedTerms))) {
          return;
        }

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
          } catch (Exception e) {
            LOG.debug("Client is gone, stopping the pipeline: {}", e.toString());
            closed = true;
            return false;
          }
        }
        return true;
      }
    }
  }

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

  private static final class BadInputException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    BadInputException(String message) {
      super(message, null, false, false);
    }
  }
}
