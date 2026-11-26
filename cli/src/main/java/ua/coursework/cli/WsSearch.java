package ua.coursework.cli;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class WsSearch {

  private final HttpClient http;
  private final ServerUrl url;

  public WsSearch(HttpClient http, ServerUrl url) {
    this.http = http;
    this.url = url;
  }

  public void search(String requestJson, Duration timeout, StageRenderer renderer)
      throws CliException {
    CompletableFuture<Void> finished = new CompletableFuture<>();
    WebSocket socket = connect(finished, renderer, timeout);
    try {
      socket.sendText(requestJson, true).get(10, TimeUnit.SECONDS);
      finished.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      closeQuietly(socket);
    } catch (TimeoutException e) {
      throw new CliException(
          "Таймаут: сервер не завершив відповідь за "
              + timeout.toSeconds()
              + " с. Збільште --timeout.",
          e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof CliException cli) {
        throw cli;
      }
      throw new CliException("Помилка WebSocket: " + cause, cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CliException("Перервано.", e);
    } finally {
      socket.abort();
    }
  }

  private WebSocket connect(
      CompletableFuture<Void> finished, StageRenderer renderer, Duration timeout)
      throws CliException {
    try {
      return http.newWebSocketBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .buildAsync(URI.create(url.ws()), new Listener(finished, renderer))
          .get(15, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
        throw ServerClient.unavailable(cause, url);
      }
      if (cause instanceof WebSocketHandshakeException handshake) {
        throw new CliException(
            "Сервер "
                + url.http()
                + " не прийняв WebSocket /ws/search (HTTP "
                + handshake.getResponse().statusCode()
                + "). Спробуйте --rest.",
            cause);
      }
      throw new CliException("Не вдалося підключитися до " + url.ws() + ": " + cause, cause);
    } catch (TimeoutException e) {
      throw ServerClient.unavailable(e, url);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CliException("Перервано.", e);
    }
  }

  private static void closeQuietly(WebSocket socket) {
    try {
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(2, TimeUnit.SECONDS);
    } catch (Exception e) {
    }
  }

  private static final class Listener implements WebSocket.Listener {

    private final CompletableFuture<Void> finished;
    private final StageRenderer renderer;
    private final StringBuilder partial = new StringBuilder();

    Listener(CompletableFuture<Void> finished, StageRenderer renderer) {
      this.finished = finished;
      this.renderer = renderer;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        String message = partial.toString();
        partial.setLength(0);
        try {
          if (renderer.accept(message)) {
            finished.complete(null);
          }
        } catch (RuntimeException e) {
          finished.completeExceptionally(e);
        }
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      finished.completeExceptionally(
          new CliException("Сервер закрив з'єднання до завершення пошуку (код " + statusCode + ")."));
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      finished.completeExceptionally(
          new CliException("З'єднання з сервером перервано: " + error, error));
    }
  }
}
