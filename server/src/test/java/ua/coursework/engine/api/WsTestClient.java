package ua.coursework.engine.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

final class WsTestClient implements AutoCloseable {

  private final Object lock = new Object();
  private final List<String> received = new ArrayList<>();
  private boolean closed;
  private WebSocket socket;

  static WsTestClient connect(int port) throws Exception {
    WsTestClient client = new WsTestClient();
    client.socket =
        HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(URI.create("ws://localhost:" + port + "/ws/search"), client.new Listener())
            .get(10, TimeUnit.SECONDS);
    return client;
  }

  void send(String text) throws Exception {
    socket.sendText(text, true).get(10, TimeUnit.SECONDS);
  }

  String next(long timeoutMillis) throws InterruptedException {
    long deadline = System.nanoTime() + timeoutMillis * 1_000_000;
    synchronized (lock) {
      while (received.isEmpty()) {
        long left = (deadline - System.nanoTime()) / 1_000_000;
        if (left <= 0 || closed) {
          return received.isEmpty() ? null : received.remove(0);
        }
        lock.wait(left);
      }
      return received.remove(0);
    }
  }

  List<String> readUntilDone(long timeoutMillis) throws InterruptedException {
    List<String> messages = new ArrayList<>();
    while (true) {
      String message = next(timeoutMillis);
      if (message == null) {
        throw new AssertionError("No 'done' message in time, got so far: " + messages);
      }
      messages.add(message);
      if (message.contains("\"stage\":\"done\"")) {
        return messages;
      }
    }
  }

  boolean isClosed() {
    synchronized (lock) {
      return closed;
    }
  }

  void abort() {
    socket.abort();
  }

  @Override
  public void close() {
    if (!socket.isOutputClosed()) {
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
    }
  }

  private final class Listener implements WebSocket.Listener {
    private final StringBuilder partial = new StringBuilder();

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        synchronized (lock) {
          received.add(partial.toString());
          lock.notifyAll();
        }
        partial.setLength(0);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      markClosed();
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      markClosed();
    }

    private void markClosed() {
      synchronized (lock) {
        closed = true;
        lock.notifyAll();
      }
    }
  }
}
