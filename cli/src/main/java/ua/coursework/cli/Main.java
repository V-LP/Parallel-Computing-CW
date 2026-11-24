package ua.coursework.cli;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Entry point of the console client: {@code java -jar search-cli.jar "white whale"}. */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    // Always UTF-8, whatever the default encoding of the console is (see README, "chcp 65001").
    PrintStream out =
        new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    PrintStream err =
        new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
    int code = new Cli(out, err, System.in, System::getenv).run(args);
    out.flush();
    err.flush();
    System.exit(code);
  }
}
