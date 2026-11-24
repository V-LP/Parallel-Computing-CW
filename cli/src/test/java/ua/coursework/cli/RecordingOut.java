package ua.coursework.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Captures what a program prints and remembers when each line was finished. */
final class RecordingOut {

  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final List<String> lines = new ArrayList<>(); // guarded by this
  private final List<Long> lineTimesNanos = new ArrayList<>(); // guarded by this
  private final PrintStream stream;

  RecordingOut() {
    java.io.OutputStream timing =
        new java.io.OutputStream() {
          private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

          @Override
          public void write(int b) {
            synchronized (RecordingOut.this) {
              bytes.write(b);
              if (b == '\n') {
                lines.add(pending.toString(StandardCharsets.UTF_8).replace("\r", ""));
                lineTimesNanos.add(System.nanoTime());
                pending.reset();
              } else {
                pending.write(b);
              }
            }
          }
        };
    stream = new PrintStream(timing, true, StandardCharsets.UTF_8);
  }

  PrintStream stream() {
    return stream;
  }

  synchronized String text() {
    return bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
  }

  synchronized List<String> lines() {
    return new ArrayList<>(lines);
  }

  /** Time (System.nanoTime) when the first line that contains the text was finished, or -1. */
  synchronized long timeOfLineContaining(String part) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains(part)) {
        return lineTimesNanos.get(i);
      }
    }
    return -1;
  }
}
