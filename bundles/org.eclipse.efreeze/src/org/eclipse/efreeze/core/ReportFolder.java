package org.eclipse.efreeze.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The folder of the reports: one text file by freeze, named after the time it started and how long it lasted, and one file by
 * session of Eclipse with its versions.
 */
public class ReportFolder {

  public static final String FREEZE = "freeze";
  public static final String GC_PAUSE = "gc-pause";
  private static final String SESSION = "session";
  private static final String STAMP = "\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.\\d{3}";
  private static final Pattern REPORT = Pattern.compile("(?:" + FREEZE + "|" + GC_PAUSE + ")-(" + STAMP + ").*\\.txt");
  private static final Pattern SESSION_FILE = Pattern.compile(SESSION + "-(" + STAMP + ")\\.txt");

  private final Path folder;
  private final int maxReports;
  private final DateTimeFormatter stamp;
  /** The file of this session, once it is written. */
  private String session;

  public ReportFolder(Path folder, int maxReports) {
    this(folder, maxReports, ZoneId.systemDefault());
  }

  ReportFolder(Path folder, int maxReports, ZoneId zone) {
    this.folder = folder;
    this.maxReports = Math.max(1, maxReports);
    this.stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss.SSS").withZone(zone);
  }

  public Path folder() {
    return folder;
  }

  /**
   * Starts a report: written at once, so that it is there if Eclipse never answers again.
   *
   * @param kind
   *          {@link #FREEZE} or {@link #GC_PAUSE}
   * @param start
   *          when the freeze started
   * @return the file of the report
   */
  public Path create(String kind, Instant start, String text) throws IOException {
    Files.createDirectories(folder);
    String name = kind + '-' + stamp.format(start);
    Path report = folder.resolve(name + ".txt");
    for (int other = 2; Files.exists(report); other++) {
      report = folder.resolve(name + '_' + other + ".txt");
    }
    Files.writeString(report, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    return report;
  }

  public void append(Path report, String text) throws IOException {
    Files.writeString(report, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  /**
   * Ends a report: its name tells how long the freeze lasted, then the oldest reports are deleted.
   *
   * @return the file of the report, renamed
   */
  public Path finish(Path report, long durationMillis) throws IOException {
    String name = report.getFileName().toString();
    Path finished = report.resolveSibling(name.substring(0, name.length() - ".txt".length()) + '-' + durationMillis + "ms.txt");
    try {
      report = Files.move(report, finished);
    } catch (IOException e) {
      // The report keeps its name, for example when another program reads it.
    }
    purge();
    return report;
  }

  /**
   * Writes the file of the session, once: at the first report, so that a session without a freeze leaves nothing.
   *
   * @param started
   *          when Eclipse started to be watched
   * @return the name of the file
   */
  public String session(Instant started, String text) throws IOException {
    if (session == null) {
      Files.createDirectories(folder);
      String name = SESSION + '-' + stamp.format(started) + ".txt";
      Path file = folder.resolve(name);
      if (!Files.exists(file)) {
        Files.writeString(file, text, StandardCharsets.UTF_8);
      }
      session = name;
    }
    return session;
  }

  /**
   * Keeps the newest reports, and the sessions they belong to.
   */
  void purge() throws IOException {
    List<Path> reports = new ArrayList<>();
    List<Path> sessions = new ArrayList<>();
    try (Stream<Path> files = Files.list(folder)) {
      for (Path file : (Iterable<Path>) files::iterator) {
        String name = file.getFileName().toString();
        if (REPORT.matcher(name).matches()) {
          reports.add(file);
        } else if (SESSION_FILE.matcher(name).matches()) {
          sessions.add(file);
        }
      }
    }
    // The newest first: the names start with the time.
    reports.sort(Comparator.comparing((Path file) -> stampOf(REPORT, file)).reversed());
    sessions.sort(Comparator.comparing((Path file) -> stampOf(SESSION_FILE, file)).reversed());
    for (Path report : reports.subList(Math.min(maxReports, reports.size()), reports.size())) {
      Files.deleteIfExists(report);
    }
    if (reports.isEmpty()) {
      return;
    }
    String oldest = stampOf(REPORT, reports.get(Math.min(maxReports, reports.size()) - 1));
    // The session of the oldest report is the last one that started before it: the older ones have no report left.
    boolean found = false;
    for (Path file : sessions) {
      if (stampOf(SESSION_FILE, file).compareTo(oldest) > 0) {
        continue;
      }
      if (found) {
        Files.deleteIfExists(file);
      }
      found = true;
    }
  }

  private static String stampOf(Pattern pattern, Path file) {
    Matcher matcher = pattern.matcher(file.getFileName().toString());
    return matcher.matches() ? matcher.group(1) : "";
  }
}
