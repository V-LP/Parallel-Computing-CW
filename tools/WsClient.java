import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

public class WsClient {

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
  private static final Pattern DONE = Pattern.compile("\"stage\"\\s*:\\s*\"done\"");

  public static void main(String[] args) throws Exception {
    String url = "ws://localhost:8080/ws/search";
    long timeoutSeconds = 120;
    String query = null;
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("--url") && i + 1 < args.length) {
        url = args[++i];
      } else if (args[i].equals("--timeout") && i + 1 < args.length) {
        timeoutSeconds = Long.parseLong(args[++i]);
      } else if (query == null) {
        query = args[i];
      } else {
        query = query + " " + args[i];
      }
    }
    if (query == null || query.isBlank()) {
      System.err.println("Usage: java tools/WsClient.java \"query\" [--url ws://host:port/ws/search] [--timeout seconds]");
      System.exit(2);
    }

    CompletableFuture<Void> finished = new CompletableFuture<>();
    long[] sentAt = {0};
    WebSocket.Listener listener =
        new WebSocket.Listener() {
          private final StringBuilder partial = new StringBuilder();

          @Override
          public void onOpen(WebSocket ws) {
            print("connected", 0);
            ws.request(1);
          }

          @Override
          public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
              String message = partial.toString();
              partial.setLength(0);
              print(message, (System.nanoTime() - sentAt[0]) / 1_000_000);
              if (DONE.matcher(message).find()) {
                finished.complete(null);
              }
            }
            ws.request(1);
            return null;
          }

          @Override
          public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            print("connection closed by the server (" + code + " " + reason + ")", -1);
            finished.completeExceptionally(new IllegalStateException("closed before 'done'"));
            return null;
          }

          @Override
          public void onError(WebSocket ws, Throwable error) {
            finished.completeExceptionally(error);
          }
        };

    WebSocket socket;
    try {
      socket =
          HttpClient.newHttpClient()
              .newWebSocketBuilder()
              .buildAsync(URI.create(url), listener)
              .get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      System.err.println("Cannot connect to " + url + ": " + rootMessage(e));
      System.exit(1);
      return;
    }

    String json = "{\"query\":\"" + escape(query) + "\"}";
    print("sending " + json, 0);
    sentAt[0] = System.nanoTime();
    socket.sendText(json, true).get(10, TimeUnit.SECONDS);

    try {
      finished.get(timeoutSeconds, TimeUnit.SECONDS);
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      System.err.println("No 'done' message within " + timeoutSeconds + " s");
      socket.abort();
      System.exit(1);
    } catch (Exception e) {
      System.err.println("Failed: " + rootMessage(e));
      socket.abort();
      System.exit(1);
    }
  }

  private static void print(String text, long elapsedMs) {
    String stamp = LocalTime.now().format(TIME);
    if (elapsedMs > 0) {
      System.out.println(stamp + "  [+" + elapsedMs + " ms]  " + text);
    } else {
      System.out.println(stamp + "  " + text);
    }
  }

  private static String escape(String text) {
    StringBuilder out = new StringBuilder();
    for (char c : text.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }

  private static String rootMessage(Throwable e) {
    while (e.getCause() != null) {
      e = e.getCause();
    }
    return e.toString();
  }
}
