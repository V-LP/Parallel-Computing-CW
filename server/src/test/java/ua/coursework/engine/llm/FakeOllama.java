package ua.coursework.engine.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class FakeOllama implements AutoCloseable {

  interface Reply {
    void handle(String requestBody, Responder responder) throws Exception;
  }

  interface Responder {
    void send(int status, String body) throws IOException;
  }

  private final HttpServer server;
  private final List<String> requests = new ArrayList<>();

  FakeOllama() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
  }

  FakeOllama on(String path, int status, String body) {
    return on(path, (request, responder) -> responder.send(status, body));
  }

  FakeOllama on(String path, Reply reply) {
    server.createContext(
        path,
        exchange -> {
          String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          synchronized (requests) {
            requests.add(path + " " + body);
          }
          try {
            reply.handle(body, (status, text) -> respond(exchange, status, text));
          } catch (IOException e) {
          } catch (Exception e) {
            respond(exchange, 500, "{\"error\":\"fake failure\"}");
          }
        });
    return this;
  }

  FakeOllama start() {
    server.start();
    return this;
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  int count(String path) {
    synchronized (requests) {
      return (int) requests.stream().filter(r -> r.startsWith(path + " ")).count();
    }
  }

  String lastBody(String path) {
    synchronized (requests) {
      String found = null;
      for (String r : requests) {
        if (r.startsWith(path + " ")) {
          found = r.substring(path.length() + 1);
        }
      }
      return found;
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private static void respond(HttpExchange exchange, int status, String text) throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
