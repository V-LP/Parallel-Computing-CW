package ua.coursework.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ServerUrlTest {

  @Test
  void defaultAddressIsLocalhost8080() throws Exception {
    ServerUrl url = ServerUrl.parse(null);
    assertEquals("http://localhost:8080", url.http());
    assertEquals("ws://localhost:8080/ws/search", url.ws());
    assertEquals(url, ServerUrl.parse("  "));
  }

  @Test
  void httpAddressGivesHttpAndWs() throws Exception {
    ServerUrl url = ServerUrl.parse("http://example.org:9000/");
    assertEquals("http://example.org:9000", url.http());
    assertEquals("ws://example.org:9000/ws/search", url.ws());
  }

  @Test
  void httpsGivesWss() throws Exception {
    ServerUrl url = ServerUrl.parse("https://example.org");
    assertEquals("https://example.org", url.http());
    assertEquals("wss://example.org/ws/search", url.ws());
  }

  @Test
  void wsAddressIsAccepted() throws Exception {
    ServerUrl url = ServerUrl.parse("ws://host:1");
    assertEquals("http://host:1", url.http());
    assertEquals("ws://host:1/ws/search", url.ws());
  }

  @Test
  void addressWithoutSchemeIsHttp() throws Exception {
    assertEquals("http://server:8080", ServerUrl.parse("server:8080").http());
  }

  @Test
  void badAddressesAreRejected() {
    assertThrows(UsageException.class, () -> ServerUrl.parse("http://"));
    assertThrows(UsageException.class, () -> ServerUrl.parse("ftp://host"));
    assertThrows(UsageException.class, () -> ServerUrl.parse("http://bad host"));
  }
}
