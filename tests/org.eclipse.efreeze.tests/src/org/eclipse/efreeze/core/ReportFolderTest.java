package org.eclipse.efreeze.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportFolderTest {

  private static final Instant NOON = Instant.parse("2026-10-14T12:00:00.123Z");

  @TempDir
  Path temp;

  private ReportFolder folder(int maxReports) {
    return new ReportFolder(temp.resolve("reports"), maxReports, ZoneOffset.UTC);
  }

  private List<String> files() throws IOException {
    try (Stream<Path> files = Files.list(temp.resolve("reports"))) {
      return files.map(file -> file.getFileName().toString()).sorted().toList();
    }
  }

  @Test
  void reportIsWrittenAtOnceThenNamedAfterItsDuration() throws IOException {
    ReportFolder folder = folder(10);
    assertEquals(temp.resolve("reports"), folder.folder());
    Path report = folder.create(ReportFolder.FREEZE, NOON, "start\n");
    assertEquals(temp.resolve("reports/freeze-2026-10-14_12-00-00.123.txt"), report);
    assertEquals("start\n", Files.readString(report), "there if Eclipse is killed now");
    folder.append(report, "more\n");
    assertEquals("start\nmore\n", Files.readString(report));
    Path finished = folder.finish(report, 2350);
    assertEquals(temp.resolve("reports/freeze-2026-10-14_12-00-00.123-2350ms.txt"), finished);
    assertEquals("start\nmore\n", Files.readString(finished));
    assertEquals(List.of("freeze-2026-10-14_12-00-00.123-2350ms.txt"), files());

    // Two freezes in the same millisecond, and a pause of the garbage collector.
    Path same = folder.create(ReportFolder.FREEZE, NOON, "a");
    Path again = folder.create(ReportFolder.FREEZE, NOON, "b");
    assertEquals("freeze-2026-10-14_12-00-00.123.txt", same.getFileName().toString());
    assertEquals("freeze-2026-10-14_12-00-00.123_2.txt", again.getFileName().toString());
    assertEquals("gc-pause-2026-10-14_12-00-01.123-900ms.txt",
        folder.finish(folder.create(ReportFolder.GC_PAUSE, NOON.plusSeconds(1), "gc"), 900).getFileName().toString());
  }

  @Test
  void sessionIsWrittenOnceAtTheFirstReport() throws IOException {
    ReportFolder folder = folder(10);
    assertFalse(Files.exists(temp.resolve("reports")), "nothing without a freeze");
    assertEquals("session-2026-10-14_12-00-00.123.txt", folder.session(NOON, "versions"));
    assertEquals("session-2026-10-14_12-00-00.123.txt", folder.session(NOON.plusSeconds(5), "other"));
    assertEquals(List.of("session-2026-10-14_12-00-00.123.txt"), files());
    assertEquals("versions", Files.readString(temp.resolve("reports/session-2026-10-14_12-00-00.123.txt")));
    // The watchdog started again in the same session, after a change of the preferences.
    assertEquals("session-2026-10-14_12-00-00.123.txt", folder(10).session(NOON, "again"));
    assertEquals("versions", Files.readString(temp.resolve("reports/session-2026-10-14_12-00-00.123.txt")));
  }

  @Test
  void oldestReportsAreDeletedWithTheSessionsThatHaveNoneLeft() throws IOException {
    // Three sessions: two reports in the first one, none in the second, two in the third.
    ReportFolder first = folder(3);
    first.session(NOON, "first");
    first.finish(first.create(ReportFolder.FREEZE, NOON.plusSeconds(10), "1"), 1000);
    first.finish(first.create(ReportFolder.GC_PAUSE, NOON.plusSeconds(20), "2"), 2000);
    folder(3).session(NOON.plusSeconds(100), "second");
    ReportFolder third = folder(3);
    third.session(NOON.plusSeconds(200), "third");
    third.finish(third.create(ReportFolder.FREEZE, NOON.plusSeconds(210), "3"), 3000);
    Files.writeString(temp.resolve("reports/notes.txt"), "of the user");
    assertEquals(List.of("freeze-2026-10-14_12-00-10.123-1000ms.txt", "freeze-2026-10-14_12-03-30.123-3000ms.txt",
        "gc-pause-2026-10-14_12-00-20.123-2000ms.txt", "notes.txt", "session-2026-10-14_12-00-00.123.txt",
        "session-2026-10-14_12-01-40.123.txt", "session-2026-10-14_12-03-20.123.txt"), files(), "three reports: all are kept");

    // A fourth report, not finished yet: the oldest one goes, its session still has a report.
    Path fourth = third.create(ReportFolder.FREEZE, NOON.plusSeconds(220), "4");
    third.purge();
    assertEquals(List.of("freeze-2026-10-14_12-03-30.123-3000ms.txt", "freeze-2026-10-14_12-03-40.123.txt",
        "gc-pause-2026-10-14_12-00-20.123-2000ms.txt", "notes.txt", "session-2026-10-14_12-00-00.123.txt",
        "session-2026-10-14_12-01-40.123.txt", "session-2026-10-14_12-03-20.123.txt"), files());

    // A fifth one: the first session has no report left, the second one never had any.
    third.finish(fourth, 4000);
    third.finish(third.create(ReportFolder.FREEZE, NOON.plusSeconds(230), "5"), 5000);
    assertEquals(List.of("freeze-2026-10-14_12-03-30.123-3000ms.txt", "freeze-2026-10-14_12-03-40.123-4000ms.txt",
        "freeze-2026-10-14_12-03-50.123-5000ms.txt", "notes.txt", "session-2026-10-14_12-03-20.123.txt"), files());

    // At least one report is kept.
    ReportFolder one = folder(0);
    one.purge();
    assertEquals(List.of("freeze-2026-10-14_12-03-50.123-5000ms.txt", "notes.txt", "session-2026-10-14_12-03-20.123.txt"), files());
  }

  @Test
  void folderThatCannotBeCreatedIsAnError() throws IOException {
    Path file = Files.writeString(temp.resolve("file"), "not a folder");
    ReportFolder folder = new ReportFolder(file.resolve("reports"), 10);
    assertThrows(IOException.class, () -> folder.create(ReportFolder.FREEZE, NOON, "start"));
    assertThrows(IOException.class, () -> folder.session(NOON, "versions"));
  }
}
