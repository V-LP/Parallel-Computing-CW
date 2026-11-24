package ua.coursework.cli;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Runs the {@link Cli} in a test and returns what it printed. */
final class CliTestSupport {

  record Result(int code, String out, String err, RecordingOut recording) {}

  private CliTestSupport() {}

  static Result run(String stdin, Map<String, String> env, String... args) {
    RecordingOut out = new RecordingOut();
    RecordingOut err = new RecordingOut();
    Cli cli =
        new Cli(
            out.stream(),
            err.stream(),
            new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
            env::get);
    int code = cli.run(args);
    return new Result(code, out.text(), err.text(), out);
  }

  static Result run(String... args) {
    return run("", Map.of(), args);
  }

  /** An address where nothing listens: the port of a server that was just closed. */
  static String deadUrl() throws Exception {
    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      return "http://127.0.0.1:" + socket.getLocalPort();
    }
  }
}
