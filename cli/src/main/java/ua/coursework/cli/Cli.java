package ua.coursework.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import ua.coursework.cli.CliOptions.Command;

public final class Cli {

  public static final int EXIT_OK = 0;

  public static final int EXIT_FAILURE = 1;
  public static final int EXIT_USAGE = 2;

  static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(120);
  static final Duration QUICK_TIMEOUT = Duration.ofSeconds(10);
  static final Duration INDEX_TIMEOUT = Duration.ofMinutes(30);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final PrintStream out;
  private final PrintStream err;
  private final InputStream in;
  private final Function<String, String> env;

  public Cli(PrintStream out, PrintStream err, InputStream in, Function<String, String> env) {
    this.out = out;
    this.err = err;
    this.in = in;
    this.env = env;
  }

  public int run(String[] args) {
    try {
      CliOptions options = ArgsParser.parse(args);
      if (options.command() == Command.HELP) {
        out.print(helpText());
        return EXIT_OK;
      }
      execute(options);
      return EXIT_OK;
    } catch (UsageException e) {
      err.println(e.getMessage());
      err.println("Використання: search \"запит\" [параметри]. Довідка: search --help");
      return EXIT_USAGE;
    } catch (CliException e) {
      err.println(e.getMessage());
      return EXIT_FAILURE;
    } catch (SearchFailedException e) {
      return EXIT_FAILURE; // message already printed
    }
  }

  private void execute(CliOptions options)
      throws UsageException, CliException, SearchFailedException {
    String rawUrl = options.url() != null ? options.url() : env.apply("SEARCH_URL");
    ServerUrl url = ServerUrl.parse(rawUrl);
    HttpClient http = ServerClient.newHttpClient();
    ServerClient client = new ServerClient(url, http);
    switch (options.command()) {
      case SEARCH -> search(options, url, http, client);
      case HEALTH -> health(options, client);
      case STATS -> stats(options, client);
      case INDEX -> index(options, client);
      case HELP -> out.print(helpText());
    }
  }

  private void search(CliOptions options, ServerUrl url, HttpClient http, ServerClient client)
      throws UsageException, CliException, SearchFailedException {
    String query = readQuery(options.query());
    Duration timeout = timeoutOf(options, SEARCH_TIMEOUT);
    if (options.rest()) {
      Map<String, Object> request = new LinkedHashMap<>();
      request.put("query", query);
      request.put("mode", "keyword");
      String body = json(request);
      String response = client.post("/api/search", body, timeout);
      if (options.json()) {
        out.println(response);
        return;
      }
      JsonNode root = parseResponse(response);
      long timeMs = root.has("time_ms") ? root.get("time_ms").asLong() : 0;
      ResultFormatter.printResults(out, root.get("results"), timeMs);
      out.println("Готово.");
    } else {
      StageRenderer renderer = new StageRenderer(out, err, options.json());
      new WsSearch(http, url).search(json(Map.of("query", query)), timeout, renderer);
      if (renderer.failed()) {
        throw new SearchFailedException();
      }
    }
  }

  private void health(CliOptions options, ServerClient client) throws CliException {
    String response = client.get("/api/health", timeoutOf(options, QUICK_TIMEOUT));
    if (options.json()) {
      out.println(response);
      return;
    }
    JsonNode root = parseResponse(response);
    out.println("Сервер: " + text(root, "status"));
    boolean ollama = root.has("ollama") && root.get("ollama").asBoolean();
    out.println("Ollama: " + (ollama ? "доступна" : "недоступна (пошук працює без розширення запиту)"));
  }

  private void stats(CliOptions options, ServerClient client) throws CliException {
    String response = client.get("/api/stats", timeoutOf(options, QUICK_TIMEOUT));
    if (options.json()) {
      out.println(response);
      return;
    }
    JsonNode root = parseResponse(response);
    long documents = root.path("documents").asLong();
    out.println("Документів: " + documents);
    out.println("Час побудови індексу: " + root.path("index_build_time_ms").asLong() + " мс");
    out.println("Потоків індексації: " + root.path("threads_used").asInt());
    if (documents == 0) {
      out.println("Індекс порожній. Запустіть індексацію: search --index");
    }
  }

  private void index(CliOptions options, ServerClient client) throws CliException {
    Map<String, Object> body =
        options.indexThreads() == null ? Map.of() : Map.of("threads", options.indexThreads());
    if (!options.json()) {
      err.println("Індексація запущена, це може тривати довго...");
    }
    String response = client.post("/api/index", json(body), timeoutOf(options, INDEX_TIMEOUT));
    if (options.json()) {
      out.println(response);
      return;
    }
    JsonNode root = parseResponse(response);
    long timeMs = root.path("time_ms").asLong();
    out.println("Проіндексовано документів: " + root.path("documents_indexed").asLong());
    out.println("Час: " + timeMs + " мс (" + String.format(Locale.ROOT, "%.1f", timeMs / 1000.0) + " с)");
  }

  private String readQuery(String query) throws UsageException {
    if (!query.equals("-")) {
      return query;
    }
    String text;
    try {
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UsageException("Не вдалося прочитати запит зі stdin: " + e.getMessage());
    }
    text = text.replace("\uFEFF", "").replaceAll("\\s+", " ").trim();
    if (text.isEmpty()) {
      throw new UsageException("Порожній запит (stdin порожній).");
    }
    return text;
  }

  private static Duration timeoutOf(CliOptions options, Duration defaultValue) {
    return options.timeoutSeconds() == null
        ? defaultValue
        : Duration.ofSeconds(options.timeoutSeconds());
  }

  private static JsonNode parseResponse(String body) throws CliException {
    try {
      JsonNode root = MAPPER.readTree(body);
      if (root != null && root.isObject()) {
        return root;
      }
    } catch (IOException e) {
    }
    throw new CliException("Сервер повернув відповідь не у форматі JSON.");
  }

  private static String json(Map<String, ?> value) throws CliException {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (IOException e) {
      throw new CliException("Не вдалося сформувати запит: " + e.getMessage(), e);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null ? "?" : value.asText();
  }

  private static final class SearchFailedException extends Exception {
    private static final long serialVersionUID = 1L;

    SearchFailedException() {
      super(null, null, false, false);
    }
  }

  static String helpText() {
    return """
        Консольний клієнт пошукової системи.

        Використання:
          search "запит" [параметри]      пошук (за замовчуванням через WebSocket)
          search --health                 стан сервера і Ollama
          search --stats                  статистика індексу
          search --index [потоки]         побудувати індекс (довго)
          search --help                   ця довідка

        Параметри:
          --rest            пошук через POST /api/search (без розширення запиту)
          --json            друкувати сирий JSON сервера
          --url АДРЕСА      адреса сервера (за замовчуванням $SEARCH_URL або http://localhost:8080)
          --timeout СЕК     таймаут очікування (пошук: 120 с, --index: 30 хв)

        Запит можна не брати в лапки: search white whale. "-" замість запиту читає його зі stdin (UTF-8).

        Коди виходу: 0 успіх, 1 сервер недоступний / помилка сервера / таймаут, 2 неправильні аргументи.
        """;
  }
}
