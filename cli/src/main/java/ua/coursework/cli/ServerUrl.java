package ua.coursework.cli;

import java.net.URI;

/**
 * Address of the server. Accepts http(s):// and ws(s):// addresses, or just host:port, and gives
 * the HTTP base (for REST) and the WebSocket address.
 *
 * @param http base address for REST, for example {@code http://localhost:8080} (no trailing slash)
 * @param ws address of the WebSocket, for example {@code ws://localhost:8080/ws/search}
 */
public record ServerUrl(String http, String ws) {

  public static final String DEFAULT = "http://localhost:8080";

  /** Reads the address; null or blank gives {@link #DEFAULT}. */
  public static ServerUrl parse(String raw) throws UsageException {
    String text = raw == null || raw.isBlank() ? DEFAULT : raw.trim();
    if (!text.contains("://")) {
      text = "http://" + text;
    }
    int authorityStart = text.indexOf("://") + 3;
    while (text.length() > authorityStart && text.endsWith("/")) {
      text = text.substring(0, text.length() - 1);
    }
    URI uri;
    try {
      uri = URI.create(text);
    } catch (IllegalArgumentException e) {
      throw new UsageException("Некоректна адреса сервера: " + raw);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
    if (uri.getHost() == null
        || !(scheme.equals("http")
            || scheme.equals("https")
            || scheme.equals("ws")
            || scheme.equals("wss"))) {
      throw new UsageException(
          "Некоректна адреса сервера: " + raw + " (очікується, наприклад, http://localhost:8080)");
    }
    boolean secure = scheme.equals("https") || scheme.equals("wss");
    String rest = text.substring(text.indexOf("://") + 3);
    return new ServerUrl(
        (secure ? "https://" : "http://") + rest, (secure ? "wss://" : "ws://") + rest + "/ws/search");
  }
}
