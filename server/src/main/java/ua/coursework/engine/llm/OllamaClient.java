package ua.coursework.engine.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** HTTP client for Ollama. Stage 2 only checks availability; generation comes in stage 8. */
public class OllamaClient {

  private static final Logger LOG = LoggerFactory.getLogger(OllamaClient.class);
  private static final String DEFAULT_URL = "http://ollama:11434";
  private static final Duration TIMEOUT = Duration.ofSeconds(2);

  private final String baseUrl;
  private final HttpClient http;

  public OllamaClient(String baseUrl) {
    this.baseUrl = stripTrailingSlash(baseUrl);
    this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  /** Creates a client from the OLLAMA_URL environment variable (or the default). */
  public static OllamaClient fromEnv() {
    String url = System.getenv("OLLAMA_URL");
    return new OllamaClient(url == null || url.isBlank() ? DEFAULT_URL : url);
  }

  /** Returns true if Ollama answers GET /api/tags with HTTP 200 within 2 seconds. Never throws. */
  public boolean isAvailable() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags")).timeout(TIMEOUT).GET().build();
      HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
      return response.statusCode() == 200;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (Exception e) {
      LOG.debug("Ollama is not available at {}: {}", baseUrl, e.toString());
      return false;
    }
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
