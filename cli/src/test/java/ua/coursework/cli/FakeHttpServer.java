package ua.coursework.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class FakeHttpServer implements AutoCloseable {

  interface Reply {
    void handle(String method, String requestBody, Responder responder) throws Exception;
  }

  interface Responder {
    void send(int status, String body) throws IOException;
  }

  private final HttpServer server;
  private final List<String> requests = new ArrayList<>();

  FakeHttpServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
  }

  FakeHttpServer on(String path, int status, String body) {
    return on(path, (method, request, responder) -> responder.send(status, body));
  }

  FakeHttpServer on(String path, Reply reply) {
    server.createContext(
        path,
        exchange -> {
          String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          synchronized (requests) {
            requests.add(exchange.getRequestMethod() + " " + path + " " + body);
          }
          try {
            reply.handle(
                exchange.getRequestMethod(), body, (status, text) -> respond(exchange, status, text));
          } catch (IOException e) {
          } catch (Exception e) {
            respond(exchange, 500, "{\"status\":\"error\",\"message\":\"fake failure\"}");
          }
        });
    return this;
  }

  FakeHttpServer start() {
    server.start();
    return this;
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  String lastBody(String path) {
    synchronized (requests) {
      for (int i = requests.size() - 1; i >= 0; i--) {
        String prefix = requests.get(i).substring(requests.get(i).indexOf(' ') + 1);
        if (prefix.equals(path) || prefix.startsWith(path + " ")) {
          return prefix.length() > path.length() ? prefix.substring(path.length() + 1) : "";
        }
      }
    }
    return null;
  }

  int requestCount() {
    synchronized (requests) {
      return requests.size();
    }
  }

  private static void respond(HttpExchange exchange, int status, String text) throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream body = exchange.getResponseBody()) {
      body.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
