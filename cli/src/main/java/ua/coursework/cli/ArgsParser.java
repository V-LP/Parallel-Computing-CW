package ua.coursework.cli;

import java.util.ArrayList;
import java.util.List;
import ua.coursework.cli.CliOptions.Command;

public final class ArgsParser {

  private static final int MAX_TIMEOUT_SECONDS = 24 * 60 * 60;

  private ArgsParser() {}

  public static CliOptions parse(String[] args) throws UsageException {
    for (String arg : args) {
      if (arg.equals("--help") || arg.equals("-h")) {
        return new CliOptions(Command.HELP, null, null, null, false, false, null);
      }
    }

    Command command = null;
    boolean rest = false;
    boolean json = false;
    String url = null;
    Integer timeout = null;
    Integer indexThreads = null;
    List<String> words = new ArrayList<>();
    boolean optionsEnded = false;

    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (optionsEnded || !arg.startsWith("--")) {
        words.add(arg);
        continue;
      }
      if (arg.equals("--")) { // everything after -- is the query
        optionsEnded = true;
        continue;
      }
      String name = arg;
      String inlineValue = null;
      int eq = arg.indexOf('=');
      if (eq > 0) {
        name = arg.substring(0, eq);
        inlineValue = arg.substring(eq + 1);
      }
      switch (name) {
        case "--health" -> command = choose(command, Command.HEALTH);
        case "--stats" -> command = choose(command, Command.STATS);
        case "--index" -> {
          command = choose(command, Command.INDEX);
          if (inlineValue != null) {
            indexThreads = positiveInt(inlineValue, "--index");
          } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
            indexThreads = positiveInt(args[++i], "--index");
          }
        }
        case "--rest" -> rest = true;
        case "--json" -> json = true;
        case "--url" -> {
          if (inlineValue != null) {
            url = inlineValue;
          } else {
            url = nextValue(args, i, name);
            i++;
          }
        }
        case "--timeout" -> {
          String value = inlineValue != null ? inlineValue : nextValue(args, i, name);
          if (inlineValue == null) {
            i++;
          }
          timeout = positiveInt(value, "--timeout");
          if (timeout > MAX_TIMEOUT_SECONDS) {
            throw new UsageException("--timeout: забагато секунд (максимум " + MAX_TIMEOUT_SECONDS + ")");
          }
        }
        default -> throw new UsageException("Невідомий параметр: " + arg);
      }
    }

    if (command == null) {
      command = Command.SEARCH;
    }
    String query = String.join(" ", words).trim();
    if (command == Command.SEARCH) {
      if (query.isEmpty()) {
        throw new UsageException("Порожній запит.");
      }
    } else if (!query.isEmpty()) {
      throw new UsageException("Команда " + flagOf(command) + " не приймає запит: \"" + query + "\"");
    }
    return new CliOptions(
        command, command == Command.SEARCH ? query : null, url, timeout, rest, json, indexThreads);
  }

  private static Command choose(Command current, Command wanted) throws UsageException {
    if (current != null && current != wanted) {
      throw new UsageException(
          "Можна вказати лише одну команду: " + flagOf(current) + " і " + flagOf(wanted) + " разом не працюють.");
    }
    return wanted;
  }

  private static String flagOf(Command command) {
    return switch (command) {
      case HEALTH -> "--health";
      case STATS -> "--stats";
      case INDEX -> "--index";
      case HELP -> "--help";
      case SEARCH -> "пошук";
    };
  }

  private static String nextValue(String[] args, int i, String option) throws UsageException {
    if (i + 1 >= args.length) {
      throw new UsageException("Після " + option + " потрібне значення.");
    }
    return args[i + 1];
  }

  private static int positiveInt(String value, String option) throws UsageException {
    try {
      int number = Integer.parseInt(value.trim());
      if (number >= 1) {
        return number;
      }
    } catch (NumberFormatException e) {
    }
    throw new UsageException(option + ": потрібне додатне ціле число, а не \"" + value + "\"");
  }
}
