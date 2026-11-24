package ua.coursework.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Sends the REST requests (GET and POST with a JSON body) and turns failures into messages. */
public final class ServerClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int MAX_ERROR_TEXT = 300;

  private final ServerUrl url;
  private final HttpClient http;

  public ServerClient(ServerUrl url, HttpClient http) {
    this.url = url;
    this.http = http;
  }

  /** A client that speaks HTTP/1.1 (the server is Jetty; no h2c upgrade surprises). */
  public static HttpClient newHttpClient() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  /** GET; returns the body of a 2xx answer. */
  public String get(String path, Duration timeout) throws CliException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url.http() + path))
            .timeout(timeout)
            .header("Accept", "application/json")
            .GET()
            .build();
    return send(request, timeout);
  }

  /** POST with a JSON body (sent as UTF-8); returns the body of a 2xx answer. */
  public String post(String path, String jsonBody, Duration timeout) throws CliException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url.http() + path))
            .timeout(timeout)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build();
    return send(request, timeout);
  }

  private String send(HttpRequest request, Duration timeout) throws CliException {
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (HttpConnectTimeoutException | ConnectException e) {
      throw unavailable(e);
    } catch (HttpTimeoutException e) {
      throw new CliException(
          "Сервер не відповів за " + timeout.toSeconds() + " с (таймаут). Збільште --timeout.", e);
    } catch (IOException e) {
      throw new CliException("Помилка зв'язку з сервером " + url.http() + ": " + e, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CliException("Перервано.", e);
    }
    int status = response.statusCode();
    if (status / 100 != 2) {
      throw new CliException(describeHttpError(status, response.body()));
    }
    return response.body();
  }

  /** The message for a server that cannot be reached (also used by the WebSocket client). */
  static CliException unavailable(Throwable cause, ServerUrl url) {
    return new CliException(
        "Сервер недоступний: "
            + url.http()
            + ". Перевірте, що він запущений (docker compose up), або вкажіть адресу"
            + " параметром --url чи змінною SEARCH_URL.",
        cause);
  }

  private CliException unavailable(Throwable cause) {
    return unavailable(cause, url);
  }

  /** "Помилка сервера (HTTP 409): text"; the text is the "message" of the error JSON if any. */
  static String describeHttpError(int status, String body) {
    String text = body == null ? "" : body.trim();
    try {
      JsonNode root = MAPPER.readTree(text);
      JsonNode message = root == null ? null : root.get("message");
      if (message != null && message.isTextual() && !message.asText().isBlank()) {
        text = message.asText();
      }
    } catch (Exception e) {
      // not JSON: show the body as it is
    }
    if (text.length() > MAX_ERROR_TEXT) {
      text = text.substring(0, MAX_ERROR_TEXT) + "...";
    }
    return "Помилка сервера (HTTP " + status + ")" + (text.isEmpty() ? "" : ": " + text);
  }
}
