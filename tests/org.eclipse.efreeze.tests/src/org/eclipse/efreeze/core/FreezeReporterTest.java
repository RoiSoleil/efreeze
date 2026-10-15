package org.eclipse.efreeze.core;

import static org.eclipse.efreeze.core.ThreadDumpsTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.efreeze.model.Counters;
import org.eclipse.efreeze.model.Counters.Collector;
import org.eclipse.efreeze.model.ThreadDump;
import org.eclipse.efreeze.model.ThreadStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The reports, with threads and counters of the test.
 */
class FreezeReporterTest {

  private static final Instant SESSION = Instant.parse("2026-10-14T09:00:00Z");

  @TempDir
  Path temp;
  private Path reports;
  private Instant now = Instant.parse("2026-10-14T12:00:01.012Z");
  /** What the probe reads. */
  private ThreadStack ui;
  private final ThreadStack worker = running(7, "Worker-1", frame("org.demo.Index", "build", 30));
  private List<Long> deadlocked = List.of();
  private long uiCpu = 4000;
  private long gcCount = 10;
  private long gcMillis = 200;
  private List<String> jobs = List.of("RUNNING \"Building\", thread \"Worker-1\"");
  private boolean closing;
  private boolean probeFails;
  private final List<String> logged = new ArrayList<>();
  /** The complete reports of the freezes, that may be shared. */
  private final List<Path> finished = new ArrayList<>();

  private final Probe probe = new Probe() {
    @Override
    public long uiThreadId() {
      return 1;
    }

    @Override
    public ThreadDump allThreads() {
      if (probeFails) {
        throw new IllegalStateException("no thread dump");
      }
      return new ThreadDump(List.of(worker, ui), deadlocked);
    }

    @Override
    public ThreadDump uiThread() {
      return new ThreadDump(List.of(ui), List.of());
    }

    @Override
    public Counters counters() {
      Map<String, Collector> collectors = new LinkedHashMap<>();
      collectors.put("G1 Young Generation", new Collector(gcCount, gcMillis));
      return new Counters(uiCpu, collectors);
    }

    @Override
    public List<String> jobs() {
      return jobs;
    }

    @Override
    public String heap() {
      return "512 MB used of 2048 MB";
    }
  };

  private FreezeReporter reporter;

  @BeforeEach
  void createReporter() {
    reports = temp.resolve("reports");
    ui = blocked(1, "main", "org.demo.Model@2a", 7, "Worker-1", frame("org.demo.Editor", "save", 10), frame("org.demo.Handler", "execute", 20));
    reporter = reporter(reports);
  }

  private FreezeReporter reporter(Path folder) {
    return new FreezeReporter(new ReportFolder(folder, 100, ZoneOffset.UTC), probe, 1000, SESSION, () -> "versions\n", () -> now, () -> closing,
        new FreezeReporter.Log() {
          @Override
          public void warning(String message) {
            logged.add("warning: " + message);
          }

          @Override
          public void error(String message, Throwable exception) {
            logged.add("error: " + message + " (" + exception.getMessage() + ")");
          }

          @Override
          public void finished(Path report) {
            finished.add(report);
          }
        }, ZoneOffset.ofHours(2));
  }

  private List<String> files() throws IOException {
    if (!Files.exists(reports)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(reports)) {
      return files.map(file -> file.getFileName().toString()).sorted().toList();
    }
  }

  @Test
  void reportOfAFreezeFromItsStartToItsEnd() throws IOException {
    reporter.responsive();
    assertNull(reporter.currentReport());

    // One second without an answer: the report is written with all the threads.
    uiCpu += 12;
    reporter.started(1012);
    Path report = reporter.currentReport();
    assertEquals(reports.resolve("freeze-2026-10-14_12-00-00.000.txt"), report, "named after the start of the freeze");
    String start = """
        UI freeze of Eclipse, seen by EFreeze
        Start: 2026-10-14 14:00:00.000 +02:00
        Detected after: 1012 ms without an answer of the UI thread (threshold: 1000 ms)
        Session: session-2026-10-14_09-00-00.000.txt (the versions of Eclipse, of Java, of the system and of the plug-ins)
        Below are the stacks of the UI thread and, at some times, of all the threads. "+N ms" is the time since the start of the freeze.
        A report without an "End" section is the one of an Eclipse that was closed or killed while it was frozen.

        === +1012 ms: all the threads ===
        Summary: The UI thread is BLOCKED, it waits for <org.demo.Model@2a> held by "Worker-1" #7, which is RUNNABLE in org.demo.Index.build(Index.java:30).
        CPU time of the UI thread since the start: 12 ms
        Garbage collection since the start: none
        Heap: 512 MB used of 2048 MB
        Jobs that run or wait to run:
          RUNNING "Building", thread "Worker-1"

        "main" #1 prio=5 BLOCKED (UI thread)
        \tat org.demo.Editor.save(Editor.java:10)
        \t- waiting to lock <org.demo.Model@2a> held by "Worker-1" #7
        \tat org.demo.Handler.execute(Handler.java:20)

        "Worker-1" #7 prio=5 RUNNABLE
        \tat org.demo.Index.build(Index.java:30)
        """;
    assertEquals(start, Files.readString(report));
    assertEquals(List.of("freeze-2026-10-14_12-00-00.000.txt", "session-2026-10-14_09-00-00.000.txt"), files());
    assertEquals("versions\n", Files.readString(reports.resolve("session-2026-10-14_09-00-00.000.txt")));
    assertEquals(List.of(), logged, "nothing in the log of Eclipse while it is frozen");

    // The UI thread again, in the same place, then somewhere else.
    reporter.observed(1512, false);
    String same = """

        === +1512 ms: the UI thread ===
        Summary: The UI thread is BLOCKED, it waits for <org.demo.Model@2a> held by "Worker-1" #7.
        CPU time of the UI thread since the start: 12 ms
        Garbage collection since the start: none
        Same stack and state of the UI thread as at +1012 ms.
        """;
    assertEquals(start + same, Files.readString(report));
    ui = running(1, "main", frame("org.demo.Parser", "parse", 5), frame("org.demo.Editor", "save", 12));
    uiCpu += 480;
    gcCount += 2;
    gcMillis += 30;
    reporter.observed(2012, false);
    String moved = """

        === +2012 ms: the UI thread ===
        Summary: The UI thread is RUNNABLE in org.demo.Parser.parse(Parser.java:5).
        CPU time of the UI thread since the start: 492 ms
        Garbage collection since the start: 30 ms (G1 Young Generation: 2 collections, 30 ms)

        "main" #1 prio=5 RUNNABLE (UI thread)
        \tat org.demo.Parser.parse(Parser.java:5)
        \tat org.demo.Editor.save(Editor.java:12)
        """;
    assertEquals(start + same + moved, Files.readString(report));

    // All the threads again, and a pause of the virtual machine.
    jobs = List.of();
    deadlocked = List.of(1L, 7L);
    reporter.observed(5000, true);
    reporter.paused(700);
    String all = """

        === +5000 ms: all the threads ===
        Summary: The UI thread is RUNNABLE in org.demo.Parser.parse(Parser.java:5).
        CPU time of the UI thread since the start: 492 ms
        Garbage collection since the start: 30 ms (G1 Young Generation: 2 collections, 30 ms)
        Heap: 512 MB used of 2048 MB
        Deadlock: "main" #1, "Worker-1" #7
        Jobs that run or wait to run: none

        "main" #1 prio=5 RUNNABLE (UI thread)
        \tat org.demo.Parser.parse(Parser.java:5)
        \tat org.demo.Editor.save(Editor.java:12)

        "Worker-1" #7 prio=5 RUNNABLE
        \tat org.demo.Index.build(Index.java:30)

        The whole virtual machine, this watchdog included, was paused during about 700 ms. Garbage collection since the start: 30 ms (G1 Young Generation: 2 collections, 30 ms)
        """;
    assertEquals(start + same + moved + all, Files.readString(report));

    // The UI thread answers: the end, the duration in the name of the file, and a line in the log of Eclipse.
    uiCpu += 500;
    reporter.ended(6230, 700);
    assertNull(reporter.currentReport());
    Path finished = reports.resolve("freeze-2026-10-14_12-00-00.000-6230ms.txt");
    String end = """

        === End ===
        The UI thread answered again after 6230 ms.
        CPU time of the UI thread during the freeze: 992 ms
        Garbage collection during the freeze: 30 ms (G1 Young Generation: 2 collections, 30 ms)
        The whole virtual machine was paused during about 700 ms of the freeze.
        """;
    assertEquals(start + same + moved + all + end, Files.readString(finished));
    assertEquals(List.of("freeze-2026-10-14_12-00-00.000-6230ms.txt", "session-2026-10-14_09-00-00.000.txt"), files());
    assertEquals(List.of("warning: The user interface was frozen for 6230 ms. Thread dumps: " + finished), logged);
    assertEquals(List.of(finished), this.finished, "the complete report may be shared");

    // The next freeze has its own report, with counters that start again.
    reporter.responsive();
    now = now.plusSeconds(60);
    reporter.started(1000);
    reporter.observed(1500, false);
    reporter.ended(1600, 0);
    String next = Files.readString(reports.resolve("freeze-2026-10-14_12-01-00.012-1600ms.txt"));
    assertTrue(next.contains("CPU time of the UI thread since the start: 0 ms"), next);
    assertTrue(next.contains("=== +1500 ms: the UI thread ===\n"), next);
    assertTrue(next.contains("Same stack and state of the UI thread as at +1000 ms."), next);
    assertFalse(next.contains("was paused"), next);
    assertEquals(3, files().size(), "one session for both");
  }

  /** The UI thread in the event loop of SWT, in a function of the windowing system. */
  private static ThreadStack inEventLoop(String function) {
    return running(1, "main", frame("org.eclipse.swt.internal.win32.OS", function, -2), frame("org.eclipse.swt.widgets.Display", "readAndDispatch", 3744),
        frame("org.eclipse.e4.ui.internal.workbench.swt.PartRenderingEngine$5", "run", 1160));
  }

  @Test
  void freezeOfTheWindowingSystemIsToldAndNotShared() throws IOException {
    ui = inEventLoop("PeekMessage");
    reporter.responsive();
    reporter.started(1000);
    uiCpu += 30;
    ui = inEventLoop("EndBufferedPaint");
    reporter.observed(1500, false);
    uiCpu += 40;
    reporter.ended(3750, 0);

    Path report = reports.resolve("freeze-2026-10-14_12-00-00.012-3750ms.txt");
    assertTrue(Files.readString(report).endsWith("""
        The UI thread waited for the windowing system, not for Eclipse: at each look it was in the event loop of the workbench, with only the widgets of SWT above it, and it used 70 ms of processor. Something out of Eclipse made it wait, e.g. a change of the screens, a session locked or shared, or a tool that moves the windows. This report is not shared.
        """), Files.readString(report));
    assertEquals(List.of("warning: The user interface did not answer for 3750 ms, waiting for the windowing system, not for Eclipse. Thread dumps: "
        + report), logged);
    assertEquals(List.of(), finished, "not shared");
  }

  @Test
  void freezeInTheEventLoopWithCodeOfEclipseIsAFreeze() throws IOException {
    ui = inEventLoop("PeekMessage");
    reporter.responsive();
    reporter.started(1000);
    // the UI thread runs code of a plug-in at one look
    ui = running(1, "main", frame("org.demo.Editor", "save", 10), frame("org.eclipse.swt.widgets.Display", "readAndDispatch", 3744));
    reporter.observed(1500, false);
    ui = inEventLoop("PeekMessage");
    reporter.ended(3000, 0);

    assertEquals(1, finished.size());
    assertFalse(Files.readString(finished.get(0)).contains("windowing system"));
  }

  @Test
  void pauseOfTheVirtualMachineDoesNotMakeAFreezeOfTheWindowingSystem() throws IOException {
    ui = inEventLoop("DispatchMessage");
    reporter.responsive();
    reporter.started(1000);
    // 1600 ms of processor in 2000 ms of work, and a pause of 2500 ms of the whole virtual machine
    uiCpu += 1600;
    reporter.ended(4500, 2500);

    assertEquals(1, finished.size());
    assertFalse(Files.readString(finished.get(0)).contains("windowing system"));
  }

  @Test
  void failedLookIsNoLookInTheEventLoop() throws IOException {
    ui = inEventLoop("PeekMessage");
    reporter.responsive();
    reporter.started(1000);
    probeFails = true;
    reporter.observed(1500, true);
    probeFails = false;
    reporter.ended(3000, 0);

    assertEquals(1, finished.size());
  }

  @Test
  void busyEventLoopIsAFreeze() throws IOException {
    ui = inEventLoop("DispatchMessage");
    reporter.responsive();
    reporter.started(1000);
    // SWT handles many messages: the processor is busy
    uiCpu += 1600;
    reporter.ended(3000, 0);

    assertEquals(1, finished.size());
    assertFalse(Files.readString(finished.get(0)).contains("windowing system"));
  }

  @Test
  void suspendedUiThreadOrClosingEclipseIsNoFreezeToReport() throws IOException {
    // A breakpoint of a debugger in the UI thread.
    ui = new ThreadStack(1, "main", Thread.State.RUNNABLE, false, 5, false, true, List.of(frame("org.demo.Editor", "save", 10)), null, -1, null,
        List.of(), List.of());
    reporter.responsive();
    reporter.started(1000);
    assertNull(reporter.currentReport());
    reporter.observed(1500, false);
    reporter.paused(900);
    reporter.ended(4000, 0);

    // Eclipse closes: it saves its state in the UI thread.
    ui = running(1, "main", frame("org.eclipse.ui.internal.Workbench", "close", 10));
    closing = true;
    reporter.responsive();
    reporter.started(1000);
    reporter.observed(5000, true);
    reporter.ended(6000, 0);
    assertEquals(List.of(), files());
    assertEquals(List.of(), logged);
    assertEquals(List.of(), finished);

    // The next one is reported.
    closing = false;
    reporter.responsive();
    reporter.started(1000);
    assertNotNull(reporter.currentReport());
    assertEquals(2, files().size());
  }

  @Test
  void pauseOfTheVirtualMachineIsReportedWhenTheGarbageCollectorExplainsIt() throws IOException {
    reporter.responsive();
    // A computer that was suspended: nothing was collected meanwhile.
    reporter.paused(8000);
    gcCount += 1;
    gcMillis += 300;
    reporter.paused(1000);
    assertEquals(List.of(), files(), "the collector took less than half of the pause");

    reporter.responsive();
    gcCount += 2;
    gcMillis += 3100;
    now = Instant.parse("2026-10-14T12:00:10.200Z");
    reporter.paused(3200);
    Path pause = reports.resolve("gc-pause-2026-10-14_12-00-07.000-3200ms.txt");
    assertEquals("""
        Pause of the whole Java virtual machine of Eclipse, seen by EFreeze
        Start: about 2026-10-14 14:00:07.000 +02:00
        Duration: about 3200 ms
        Garbage collection during the pause: 3100 ms (G1 Young Generation: 2 collections, 3100 ms)
        Heap after the pause: 512 MB used of 2048 MB
        Session: session-2026-10-14_09-00-00.000.txt (the versions of Eclipse, of Java, of the system and of the plug-ins)
        Every thread was stopped, the watchdog of EFreeze too: this is not a freeze of the UI thread alone, and the stacks of the threads would tell nothing. The memory of Eclipse (-Xmx) or its garbage collector is what to look at.
        """, Files.readString(pause));
    assertEquals(List.of("warning: Eclipse was paused for about 3200 ms by its garbage collector. Report: " + pause), logged);
    assertEquals(List.of(), finished, "only the freezes of the UI thread are shared");
    assertNull(reporter.currentReport());

    // Before the first look at the UI thread there is nothing to compare with.
    logged.clear();
    reporter(reports).paused(5000);
    assertEquals(List.of(), logged);
  }

  @Test
  void reportThatCannotBeWrittenIsLoggedOnce() throws IOException {
    Path file = Files.writeString(temp.resolve("file"), "not a folder");
    FreezeReporter broken = reporter(file.resolve("reports"));
    broken.responsive();
    broken.started(1000);
    assertNull(broken.currentReport());
    broken.observed(1500, false);
    broken.ended(2000, 0);
    broken.responsive();
    broken.started(1000);
    broken.ended(2000, 0);
    assertEquals(1, logged.size(), logged.toString());
    assertTrue(logged.get(0).startsWith("error: EFreeze could not write the report of a freeze in " + file.resolve("reports")), logged.get(0));

    // The watchdog goes on when the virtual machine does not answer either.
    logged.clear();
    probeFails = true;
    reporter.responsive();
    reporter.started(1000);
    reporter.ended(2000, 0);
    assertEquals(List.of("error: EFreeze could not write the report of a freeze in " + reports + " (no thread dump)"), logged);
    probeFails = false;
    reporter.responsive();
    reporter.started(1000);
    assertNotNull(reporter.currentReport(), "the next freeze is reported");
  }
}
