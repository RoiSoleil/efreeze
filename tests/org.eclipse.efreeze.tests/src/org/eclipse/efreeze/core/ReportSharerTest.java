package org.eclipse.efreeze.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.efreeze.core.ReportSharer.Answer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sharing of the reports, with a relay of the test.
 */
class ReportSharerTest {

  private static final URI RELAY = URI.create("https://relay.example.org/reports");

  @TempDir
  Path temp;
  private Path report;
  private final List<String> logged = new ArrayList<>();
  private final List<String> posted = new ArrayList<>();
  /** What the relay answers. */
  private CompletableFuture<Answer> answer;
  private LocalDate today = LocalDate.parse("2026-10-14");
  private ReportSharer sharer;

  @BeforeEach
  void createReport() throws IOException {
    report = Files.writeString(temp.resolve("freeze-2026-10-14_12-00-00.000-2350ms.txt"),
        "UI freeze of Eclipse, seen by EFreeze\nat /home/jane/workspace/demo/src/Editor.java\n");
    sharer = new ReportSharer(RELAY, (relay, text) -> {
      assertEquals(RELAY, relay);
      posted.add(text);
      return answer;
    }, "/home/jane", new ReportSharer.Log() {
      @Override
      public void info(String message) {
        logged.add("info: " + message);
      }

      @Override
      public void warning(String message, Throwable exception) {
        logged.add("warning: " + message + (exception == null ? "" : " (" + exception.getClass().getSimpleName() + ")"));
      }
    }, () -> today);
  }

  private void share() throws Exception {
    sharer.share(report).get(10, TimeUnit.SECONDS);
  }

  @Test
  void sharedReportTellsItsIssue() throws Exception {
    answer = CompletableFuture.completedFuture(new Answer(201, "{\"url\": \"https://github.com/RoiSoleil/efreeze/issues/12\"}"));

    share();

    assertEquals(List.of("UI freeze of Eclipse, seen by EFreeze\nat ~/workspace/demo/src/Editor.java\n"), posted);
    assertEquals(List.of("info: The report freeze-2026-10-14_12-00-00.000-2350ms.txt has been shared on GitHub: "
        + "https://github.com/RoiSoleil/efreeze/issues/12"), logged);
  }

  @Test
  void homeFolderIsMaskedWhereItIsAWholeFolder() {
    assertEquals("at ~/workspace and file:~/x, not /home/janet/y nor /data/home/jane/z, ~", ReportSharer.mask(
        "at /home/jane/workspace and file:/home/jane/x, not /home/janet/y nor /data/home/jane/z, /home/jane", "/home/jane", false));
    assertEquals("/HOME/JANE/x", ReportSharer.mask("/HOME/JANE/x", "/home/jane", false), "case matters out of Windows");
    assertEquals("~\\ws and file:/~/ws and ~/ws, not C:\\Users\\Jean.Dupontel",
        ReportSharer.mask("C:\\Users\\Jean.Dupont\\ws and file:/C:/Users/Jean.Dupont/ws and c:\\users\\JEAN.DUPONT/ws, not C:\\Users\\Jean.Dupontel",
            "C:\\Users\\Jean.Dupont", true));
    assertEquals("text", ReportSharer.mask("text", "/", false));
    assertEquals("text", ReportSharer.mask("text", null, false));
  }

  @Test
  void reportOverTheLimitOfTheDayIsNotShared() throws Exception {
    answer = CompletableFuture.completedFuture(new Answer(429, "{\"error\": \"3 issues have already been opened today for this address\"}"));

    share();

    assertEquals(1, posted.size());
    assertEquals(List.of("info: The report freeze-2026-10-14_12-00-00.000-2350ms.txt has not been shared on GitHub: 3 issues have already "
        + "been opened today for this address. It opens new ones tomorrow."), logged);
  }

  @Test
  void noReportIsSentTheDayTheRelayRefusedOne() throws Exception {
    answer = CompletableFuture.completedFuture(new Answer(429, "{\"error\": \"20 issues have already been opened today\"}"));
    share();
    logged.clear();

    share();
    assertEquals(1, posted.size(), "not sent again the same day");
    assertEquals(List.of(), logged);

    today = today.plusDays(1);
    answer = CompletableFuture.completedFuture(new Answer(201, "{\"url\": \"https://github.com/RoiSoleil/efreeze/issues/13\"}"));
    share();
    assertEquals(2, posted.size(), "the limits start again the next day");
  }

  @Test
  void reportIsNotSharedWhileEFreezeStops() throws Exception {
    answer = CompletableFuture.failedFuture(new CancellationException("EFreeze has stopped"));

    share();

    assertEquals(List.of(), logged, "Eclipse closes: nothing to tell");
  }

  @Test
  void sharedReportHasTheVersionsAfterItsFirstLine() throws Exception {
    Files.writeString(temp.resolve("session-2026-10-14_09-00-00.000.txt"), "Eclipse 4.41\nJava 21\n");
    Files.writeString(report, "UI freeze of Eclipse, seen by EFreeze\nSession: session-2026-10-14_09-00-00.000.txt (the versions)\nSummary: x\n");
    answer = CompletableFuture.completedFuture(new Answer(201, "{\"url\": \"u\"}"));

    share();

    assertEquals(List.of("UI freeze of Eclipse, seen by EFreeze\nVersions, from session-2026-10-14_09-00-00.000.txt:\nEclipse 4.41\nJava 21\n"
        + "End of the versions.\nSession: session-2026-10-14_09-00-00.000.txt (the versions)\nSummary: x\n"), posted);
  }

  @Test
  void middleOfALongReportIsCut() throws IOException {
    String text = "UI freeze of Eclipse, seen by EFreeze\n" + "x".repeat(ReportSharer.MAX_TEXT) + "\n=== End ===\nThe UI thread answered again.\n";

    String shared = ReportSharer.withVersions(report, text);

    assertTrue(shared.length() <= ReportSharer.MAX_TEXT, String.valueOf(shared.length()));
    assertTrue(shared.startsWith("UI freeze of Eclipse, seen by EFreeze\nxxx"));
    assertTrue(shared.endsWith("\n=== End ===\nThe UI thread answered again.\n"), "the relay needs the end");
    assertTrue(shared.contains(" characters cut by EFreeze ...]"));
  }

  @Test
  void refusedReportIsAWarning() throws Exception {
    answer = CompletableFuture.completedFuture(new Answer(400, "{\"error\": \"not a report of EFreeze\"}"));

    share();

    assertEquals(List.of("warning: The report freeze-2026-10-14_12-00-00.000-2350ms.txt could not be shared on GitHub: the relay answered 400 "
        + "{\"error\": \"not a report of EFreeze\"}"), logged);
  }

  @Test
  void unreachableRelayIsAWarning() throws Exception {
    answer = CompletableFuture.failedFuture(new ConnectException("no network"));

    share();

    assertEquals(List.of("warning: The report freeze-2026-10-14_12-00-00.000-2350ms.txt could not be shared on GitHub (ConnectException)"),
        logged);
  }

  @Test
  void deletedReportIsNotSent() throws Exception {
    Files.delete(report);

    share();

    assertEquals(List.of(), posted);
    assertEquals(List.of("warning: The report freeze-2026-10-14_12-00-00.000-2350ms.txt could not be shared on GitHub (IllegalStateException)"),
        logged);
  }
}
