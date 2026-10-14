package org.eclipse.efreeze.core;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Shares the reports of the freezes in public issues on GitHub: a report is sent to a relay, which opens the issue. No
 * account on GitHub is needed. The relay opens a limited number of issues a day.
 * <p>
 * The report is read and sent in the background: the watchdog never waits for the network.
 */
public class ReportSharer {

  /** Where the sharer tells what happened: the log of Eclipse. */
  public interface Log {

    void info(String message);

    void warning(String message, Throwable exception);
  }

  /** How a report reaches the relay. */
  public interface Transport {

    /**
     * @return the answer of the relay: its status code and its body
     */
    CompletableFuture<Answer> post(URI relay, String report);
  }

  public record Answer(int status, String body) {
  }

  private static final Pattern URL = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern ERROR = Pattern.compile("\"error\"\\s*:\\s*\"([^\"]+)\"");
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final Pattern SESSION = Pattern.compile("^Session: (\\S+)", Pattern.MULTILINE);
  /** The relay accepts 1 MB, and shows less than 65 000 characters: the middle of a longer report is cut. */
  static final int MAX_TEXT = 500_000;
  private static final int MAX_TAIL = 20_000;
  private static final int MAX_VERSIONS = 8_000;

  private final URI relay;
  private final Transport transport;
  private final String home;
  private final Log log;
  private final Supplier<LocalDate> today;
  /** The day (UTC) when the relay refused a report because its issues of the day had been opened: none is sent that day. */
  private volatile LocalDate refused;

  /**
   * @param relay
   *          the address of the relay
   * @param home
   *          the home folder of the user, replaced by {@code ~} in the report: it often tells the name of the user
   */
  public ReportSharer(URI relay, Transport transport, String home, Log log) {
    this(relay, transport, home, log, () -> LocalDate.now(ZoneOffset.UTC));
  }

  /**
   * @param today
   *          the day in UTC, when the limits of the relay start again
   */
  ReportSharer(URI relay, Transport transport, String home, Log log, Supplier<LocalDate> today) {
    this.relay = relay;
    this.transport = transport;
    this.home = home;
    this.log = log;
    this.today = today;
  }

  /**
   * @return when the report has been shared, or not: what happened is in the log
   */
  public CompletableFuture<Void> share(Path report) {
    if (today.get().equals(refused)) {
      return CompletableFuture.completedFuture(null);
    }
    return CompletableFuture.supplyAsync(() -> read(report)).thenCompose(text -> transport.post(relay, text)).handle((response, exception) -> {
      if (exception instanceof CancellationException || exception != null && exception.getCause() instanceof CancellationException) {
        // EFreeze stops, Eclipse may be closing: the report stays on the disk.
      } else if (exception != null) {
        log.warning("The report " + report.getFileName() + " could not be shared on GitHub", exception.getCause() != null ? exception.getCause() : exception);
      } else if (response.status() == 201) {
        Matcher url = URL.matcher(response.body());
        log.info("The report " + report.getFileName() + " has been shared on GitHub: " + (url.find() ? url.group(1) : response.body()));
      } else if (response.status() == 429) {
        refused = today.get();
        Matcher error = ERROR.matcher(response.body());
        log.info("The report " + report.getFileName() + " has not been shared on GitHub: "
            + (error.find() ? error.group(1) : "the relay opens no more issues today") + ". It opens new ones tomorrow.");
      } else {
        log.warning("The report " + report.getFileName() + " could not be shared on GitHub: the relay answered " + response.status() + " "
            + abbreviate(response.body()), null);
      }
      return null;
    });
  }

  /**
   * @return the transport over HTTP
   */
  public static Transport http(Supplier<HttpClient> client) {
    return (relay, report) -> {
      HttpRequest request = HttpRequest.newBuilder(relay).timeout(TIMEOUT).header("Content-Type", "text/plain; charset=utf-8")
          .header("User-Agent", "EFreeze").POST(HttpRequest.BodyPublishers.ofString(report, StandardCharsets.UTF_8)).build();
      return client.get().sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
          .thenApply(response -> new Answer(response.statusCode(), response.body()));
    };
  }

  private String read(Path report) {
    try {
      return mask(withVersions(report, Files.readString(report, StandardCharsets.UTF_8)), home, File.separatorChar == '\\');
    } catch (IOException e) {
      throw new IllegalStateException("The report " + report + " cannot be read", e);
    }
  }

  /**
   * @return the report with the versions of Eclipse, of Java, of the system and of the plug-ins after its first line, from
   *         the file of its session: the issue shows the start of a long report. The middle of a report too long for the
   *         relay is cut, its end is kept.
   */
  static String withVersions(Path report, String text) throws IOException {
    Matcher session = SESSION.matcher(text);
    Path versions = session.find() ? report.resolveSibling(session.group(1)) : null;
    if (versions != null && Files.isRegularFile(versions)) {
      String read = Files.readString(versions, StandardCharsets.UTF_8);
      String shown = read.length() <= MAX_VERSIONS ? read : read.substring(0, MAX_VERSIONS) + "\n[...]\n";
      int line = text.indexOf('\n') + 1;
      text = text.substring(0, line) + "Versions, from " + session.group(1) + ":\n" + shown + (shown.endsWith("\n") ? "" : "\n")
          + "End of the versions.\n" + text.substring(line);
    }
    if (text.length() > MAX_TEXT) {
      int head = MAX_TEXT - MAX_TAIL - 100;
      text = text.substring(0, head) + "\n[... " + (text.length() - head - MAX_TAIL) + " characters cut by EFreeze ...]\n"
          + text.substring(text.length() - MAX_TAIL);
    }
    return text;
  }

  /**
   * @param windows
   *          whether the paths are the ones of Windows, where a path may be written with any case and with both separators
   * @return the text where the home folder is replaced by {@code ~}, when it is a whole folder of a path
   */
  static String mask(String text, String home, boolean windows) {
    List<String> names = home == null ? List.of() : Arrays.stream(home.split("[/\\\\]+")).filter(name -> !name.isEmpty()).toList();
    if (names.isEmpty()) {
      return text;
    }
    String separators = windows ? "[/\\\\]+" : "/+";
    String path = names.stream().map(Pattern::quote).collect(Collectors.joining(separators, home.startsWith("/") ? "/+" : "", ""));
    Pattern pattern = Pattern.compile("(?<![\\w.-])" + path + "(?![\\w.-])", windows ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
    return pattern.matcher(text).replaceAll("~");
  }

  private static String abbreviate(String text) {
    return text.length() <= 200 ? text : text.substring(0, 200) + "...";
  }
}
