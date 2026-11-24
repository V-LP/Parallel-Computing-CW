package ua.coursework.engine.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP client for Ollama: availability check, text generation and model download. Every failure of
 * {@link #generate} and {@link #ensureModelAvailable} is reported as an {@link OllamaException}.
 */
public class OllamaClient implements TextGenerator {

  private static final Logger LOG = LoggerFactory.getLogger(OllamaClient.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static final String DEFAULT_URL = "http://ollama:11434";
  public static final String DEFAULT_MODEL = "qwen2.5:1.5b";
  public static final int DEFAULT_GENERATE_TIMEOUT_SECONDS = 60;

  private static final Duration AVAILABILITY_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration TAGS_TIMEOUT = Duration.ofSeconds(10);
  /** Downloading a model of about 1 GB can take a long time on a slow connection. */
  private static final Duration DEFAULT_PULL_TIMEOUT = Duration.ofMinutes(30);

  private static final double TEMPERATURE = 0.2;
  private static final int MAX_NEW_TOKENS = 120;
  /** How long Ollama keeps the model in memory after a request (saves the reload time). */
  private static final String KEEP_ALIVE = "30m";

  private final String baseUrl;
  private final String model;
  private final Duration generateTimeout;
  private final Duration pullTimeout;
  private final HttpClient http;

  public OllamaClient(String baseUrl) {
    this(
        baseUrl,
        DEFAULT_MODEL,
        Duration.ofSeconds(DEFAULT_GENERATE_TIMEOUT_SECONDS),
        DEFAULT_PULL_TIMEOUT);
  }

  public OllamaClient(
      String baseUrl, String model, Duration generateTimeout, Duration pullTimeout) {
    this.baseUrl = stripTrailingSlash(baseUrl);
    this.model = model;
    this.generateTimeout = generateTimeout;
    this.pullTimeout = pullTimeout;
    this.http = HttpClient.newBuilder().connectTimeout(AVAILABILITY_TIMEOUT).build();
  }

  /** Reads OLLAMA_URL, OLLAMA_MODEL and OLLAMA_TIMEOUT_SECONDS from the environment. */
  public static OllamaClient fromEnv() {
    String url = readEnv("OLLAMA_URL", DEFAULT_URL);
    String model = readEnv("OLLAMA_MODEL", DEFAULT_MODEL);
    int seconds = DEFAULT_GENERATE_TIMEOUT_SECONDS;
    String value = readEnv("OLLAMA_TIMEOUT_SECONDS", String.valueOf(seconds));
    try {
      int parsed = Integer.parseInt(value);
      if (parsed >= 1) {
        seconds = parsed;
      } else {
        LOG.warn("OLLAMA_TIMEOUT_SECONDS must be >= 1, using {}", seconds);
      }
    } catch (NumberFormatException e) {
      LOG.warn("Invalid OLLAMA_TIMEOUT_SECONDS '{}', using {}", value, seconds);
    }
    return new OllamaClient(url, model, Duration.ofSeconds(seconds), DEFAULT_PULL_TIMEOUT);
  }

  public String model() {
    return model;
  }

  /** Returns true if Ollama answers GET /api/tags with HTTP 200 within 2 seconds. Never throws. */
  public boolean isAvailable() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
              .timeout(AVAILABILITY_TIMEOUT)
              .GET()
              .build();
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

  /**
   * Sends the prompt to POST /api/generate (no streaming, temperature 0.2, at most 120 new tokens,
   * model kept loaded for 30 minutes) and returns the "response" field.
   *
   * <p>The timeout covers the whole generation, because with {@code stream:false} Ollama answers
   * only when the text is complete.
   *
   * @throws OllamaException on network error, timeout, HTTP status other than 200 or an answer
   *     that is not JSON with a text "response"
   */
  @Override
  public String generate(String prompt) throws OllamaException {
    ObjectNode body = MAPPER.createObjectNode();
    body.put("model", model);
    body.put("prompt", prompt);
    body.put("stream", false);
    body.put("keep_alive", KEEP_ALIVE);
    ObjectNode options = body.putObject("options");
    options.put("temperature", TEMPERATURE);
    options.put("num_predict", MAX_NEW_TOKENS);

    JsonNode answer = postJson("/api/generate", body, generateTimeout);
    JsonNode text = answer.get("response");
    if (text == null || !text.isTextual()) {
      throw new OllamaException("Ollama answer has no text field \"response\"");
    }
    return text.asText();
  }

  /**
   * Makes sure the configured model is present: checks GET /api/tags and, if the model is missing,
   * downloads it with POST /api/pull (no streaming). Returns normally when the model is available.
   *
   * @throws OllamaException if Ollama cannot be reached or the download fails
   */
  public void ensureModelAvailable() throws OllamaException {
    if (hasModel()) {
      LOG.info("Ollama model {} is already available", model);
      return;
    }
    LOG.info("Ollama model {} is missing, downloading it (this can take several minutes)", model);
    long start = System.nanoTime();
    ObjectNode body = MAPPER.createObjectNode();
    body.put("model", model);
    body.put("name", model); // older Ollama versions read "name", newer ones read "model"
    body.put("stream", false);
    JsonNode answer = postJson("/api/pull", body, pullTimeout);
    JsonNode error = answer.get("error");
    if (error != null) {
      throw new OllamaException("Model download failed: " + error.asText());
    }
    LOG.info("Ollama model {} downloaded in {} s", model, (System.nanoTime() - start) / 1_000_000_000L);
  }

  /**
   * Calls {@link #ensureModelAvailable()} up to {@code attempts} times, sleeping {@code pauseMillis}
   * between failed attempts (Ollama may still be starting). Logs every step and never throws.
   *
   * @return true if the model is ready, false if all attempts failed
   */
  public boolean ensureModelAvailableWithRetries(int attempts, long pauseMillis) {
    for (int attempt = 1; attempt <= attempts; attempt++) {
      try {
        ensureModelAvailable();
        LOG.info("Ollama model {} is ready, query expansion is enabled", model);
        return true;
      } catch (OllamaException e) {
        LOG.warn("Model check {}/{} failed: {}", attempt, attempts, e.getMessage());
      }
      if (attempt < attempts) {
        try {
          Thread.sleep(pauseMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    LOG.warn(
        "Ollama model {} is NOT ready; search keeps working without query expansion", model);
    return false;
  }

  private boolean hasModel() throws OllamaException {
    HttpRequest request;
    try {
      request =
          HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags")).timeout(TAGS_TIMEOUT).GET().build();
    } catch (IllegalArgumentException e) {
      throw new OllamaException("Invalid Ollama URL: " + baseUrl, e);
    }
    JsonNode answer = readJson(send(request));
    JsonNode models = answer.get("models");
    if (models == null || !models.isArray()) {
      return false;
    }
    // "qwen2.5:1.5b" must match exactly; a name without a tag means ":latest".
    String wanted = model.contains(":") ? model : model + ":latest";
    for (JsonNode entry : models) {
      for (String field : new String[] {"name", "model"}) {
        JsonNode value = entry.get(field);
        if (value != null && wanted.equals(value.asText())) {
          return true;
        }
      }
    }
    return false;
  }

  private JsonNode postJson(String path, ObjectNode body, Duration timeout)
      throws OllamaException {
    HttpRequest request;
    try {
      request =
          HttpRequest.newBuilder(URI.create(baseUrl + path))
              .timeout(timeout)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
              .build();
    } catch (IllegalArgumentException e) {
      throw new OllamaException("Invalid Ollama URL: " + baseUrl, e);
    }
    return readJson(send(request));
  }

  private HttpResponse<String> send(HttpRequest request) throws OllamaException {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OllamaException("Interrupted while waiting for Ollama", e);
    } catch (IOException e) { // includes connection errors and HttpTimeoutException
      throw new OllamaException(
          "Ollama request " + request.uri().getPath() + " failed: " + e, e);
    }
  }

  private static JsonNode readJson(HttpResponse<String> response) throws OllamaException {
    if (response.statusCode() != 200) {
      throw new OllamaException(
          "Ollama answered HTTP "
              + response.statusCode()
              + " for "
              + response.uri().getPath()
              + ": "
              + abbreviate(response.body()));
    }
    try {
      JsonNode node = MAPPER.readTree(response.body());
      if (node == null || !node.isObject()) {
        throw new OllamaException("Ollama answer is not a JSON object: " + abbreviate(response.body()));
      }
      return node;
    } catch (IOException e) {
      throw new OllamaException("Ollama answer is not valid JSON: " + abbreviate(response.body()), e);
    }
  }

  private static String abbreviate(String text) {
    if (text == null) {
      return "";
    }
    String oneLine = text.replaceAll("\\s+", " ").trim();
    return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "...";
  }

  private static String readEnv(String name, String defaultValue) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value.trim();
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
