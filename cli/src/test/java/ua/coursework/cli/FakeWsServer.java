package ua.coursework.cli;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class FakeWsServer implements AutoCloseable {

  private interface Step {
    void run(OutputStream out) throws Exception;
  }

  private final ServerSocket serverSocket;
  private final List<Step> script = new ArrayList<>();
  private final List<Socket> sockets = new ArrayList<>();
  private final List<String> received = new ArrayList<>();
  private volatile int handshakeStatus = 101;
  private final Thread acceptor;

  FakeWsServer() throws IOException {
    serverSocket = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
    acceptor = new Thread(this::acceptLoop, "fake-ws-acceptor");
    acceptor.setDaemon(true);
  }

  FakeWsServer send(String text) {
    script.add(out -> writeFrame(out, 0x1, text.getBytes(StandardCharsets.UTF_8)));
    return this;
  }

  FakeWsServer sleep(long millis) {
    script.add(out -> Thread.sleep(millis));
    return this;
  }

  FakeWsServer closeConnection() {
    script.add(out -> writeFrame(out, 0x8, new byte[] {0x03, (byte) 0xE8}));
    return this;
  }

  FakeWsServer hang() {
    script.add(out -> Thread.sleep(60_000));
    return this;
  }

  FakeWsServer refuseWith(int status) {
    handshakeStatus = status;
    return this;
  }

  FakeWsServer start() {
    acceptor.start();
    return this;
  }

  String url() {
    return "http://127.0.0.1:" + serverSocket.getLocalPort();
  }

  List<String> received() {
    synchronized (received) {
      return new ArrayList<>(received);
    }
  }

  private void acceptLoop() {
    while (!serverSocket.isClosed()) {
      try {
        Socket socket = serverSocket.accept();
        synchronized (sockets) {
          sockets.add(socket);
        }
        Thread handler = new Thread(() -> serve(socket), "fake-ws-connection");
        handler.setDaemon(true);
        handler.start();
      } catch (IOException e) {
        return;
      }
    }
  }

  private void serve(Socket socket) {
    try {
      InputStream in = socket.getInputStream();
      OutputStream out = socket.getOutputStream();
      String key = readHandshake(in);
      if (handshakeStatus != 101) {
        out.write(
            ("HTTP/1.1 " + handshakeStatus + " Refused\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return;
      }
      String accept =
          Base64.getEncoder()
              .encodeToString(
                  MessageDigest.getInstance("SHA-1")
                      .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
      out.write(
          ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                  + "Sec-WebSocket-Accept: "
                  + accept
                  + "\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.flush();

      String text = readTextFrame(new DataInputStream(in));
      if (text != null) {
        synchronized (received) {
          received.add(text);
        }
      }
      for (Step step : script) {
        step.run(out);
      }
      while (in.read() >= 0) {
      }
    } catch (Exception e) {
    } finally {
      try {
        socket.close();
      } catch (IOException e) {
      }
    }
  }

  private static String readHandshake(InputStream in) throws IOException {
    StringBuilder header = new StringBuilder();
    while (!header.toString().endsWith("\r\n\r\n")) {
      int b = in.read();
      if (b < 0) {
        throw new IOException("closed during the handshake");
      }
      header.append((char) b);
    }
    for (String line : header.toString().split("\r\n")) {
      if (line.toLowerCase().startsWith("sec-websocket-key:")) {
        return line.substring(line.indexOf(':') + 1).trim();
      }
    }
    throw new IOException("no Sec-WebSocket-Key");
  }

  private static String readTextFrame(DataInputStream in) throws IOException {
    int first = in.readUnsignedByte();
    int second = in.readUnsignedByte();
    long length = second & 0x7F;
    if (length == 126) {
      length = in.readUnsignedShort();
    } else if (length == 127) {
      length = in.readLong();
    }
    byte[] mask = new byte[4];
    if ((second & 0x80) != 0) {
      in.readFully(mask);
    }
    byte[] payload = new byte[(int) length];
    in.readFully(payload);
    for (int i = 0; i < payload.length; i++) {
      payload[i] ^= mask[i % 4];
    }
    return (first & 0x0F) == 0x1 ? new String(payload, StandardCharsets.UTF_8) : null;
  }

  private static void writeFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
    out.write(0x80 | opcode);
    if (payload.length < 126) {
      out.write(payload.length);
    } else {
      out.write(126);
      out.write(payload.length >> 8);
      out.write(payload.length & 0xFF);
    }
    out.write(payload);
    out.flush();
  }

  @Override
  public void close() {
    try {
      serverSocket.close();
    } catch (IOException e) {
    }
    synchronized (sockets) {
      for (Socket socket : sockets) {
        try {
          socket.close();
        } catch (IOException e) {
        }
      }
    }
  }
}
