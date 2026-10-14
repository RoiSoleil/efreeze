package org.eclipse.efreeze.core;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.eclipse.efreeze.model.Counters;
import org.eclipse.efreeze.model.ThreadDump;
import org.eclipse.efreeze.model.ThreadStack;

/**
 * Writes the report of each freeze that the detector sees: the thread dumps are in the file as soon as the freeze is
 * detected, so that they are there even if Eclipse never answers again and is killed.
 */
public class FreezeReporter implements FreezeDetector.Listener {

  /** Where the reporter tells what it has written: the log of Eclipse. */
  public interface Log {

    void warning(String message);

    void error(String message, Throwable exception);
  }

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xxx");

  private final ReportFolder folder;
  private final Probe probe;
  private final long threshold;
  private final Instant sessionStart;
  private final Supplier<String> session;
  private final Supplier<Instant> now;
  private final BooleanSupplier ignored;
  private final Log log;
  private final ZoneId zone;

  /** The counters when the UI thread was asked last: at the start of a freeze, if one follows. */
  private Counters baseline;
  /** The report of the freeze that is going on. */
  private Path report;
  /** The freeze that is going on is not reported. */
  private boolean ignoring;
  /** The UI thread when it was looked at last, and when. */
  private ThreadStack lastUi;
  private long lastUiAt;
  /** An error is logged once, not at each look at a freeze. */
  private boolean failed;

  /**
   * @param threshold
   *          the time without an answer that makes a freeze, for the reports
   * @param sessionStart
   *          when Eclipse started to be watched
   * @param session
   *          the versions of Eclipse and of its plug-ins, see {@link SessionInfo}
   * @param now
   *          the time of the system
   * @param ignored
   *          tells that a freeze is not one to report: Eclipse is closing
   */
  public FreezeReporter(ReportFolder folder, Probe probe, long threshold, Instant sessionStart, Supplier<String> session,
      Supplier<Instant> now, BooleanSupplier ignored, Log log) {
    this(folder, probe, threshold, sessionStart, session, now, ignored, log, ZoneId.systemDefault());
  }

  FreezeReporter(ReportFolder folder, Probe probe, long threshold, Instant sessionStart, Supplier<String> session, Supplier<Instant> now,
      BooleanSupplier ignored, Log log, ZoneId zone) {
    this.folder = folder;
    this.probe = probe;
    this.threshold = threshold;
    this.sessionStart = sessionStart;
    this.session = session;
    this.now = now;
    this.ignored = ignored;
    this.log = log;
    this.zone = zone;
  }

  /**
   * @return the report of the freeze that is going on, or {@code null}
   */
  public Path currentReport() {
    return report;
  }

  @Override
  public void responsive() {
    try {
      baseline = probe.counters();
    } catch (RuntimeException e) {
      failed(e);
    }
  }

  @Override
  public void started(long elapsedMillis) {
    try {
      ThreadDump dump = probe.allThreads();
      ThreadStack ui = dump.thread(probe.uiThreadId());
      // A UI thread suspended by a debugger, or an Eclipse that closes, does not answer either.
      if (ui != null && ui.suspended() || ignored.getAsBoolean()) {
        ignoring = true;
        return;
      }
      Instant start = now.get().minusMillis(elapsedMillis);
      StringBuilder text = new StringBuilder("UI freeze of Eclipse, seen by EFreeze\n");
      text.append("Start: ").append(TIME.withZone(zone).format(start)).append('\n');
      text.append("Detected after: ").append(elapsedMillis).append(" ms without an answer of the UI thread (threshold: ").append(threshold)
          .append(" ms)\n");
      text.append("Session: ").append(folder.session(sessionStart, session.get()))
          .append(" (the versions of Eclipse, of Java, of the system and of the plug-ins)\n");
      text.append("Below are the stacks of the UI thread and, at some times, of all the threads. \"+N ms\" is the time since the start"
          + " of the freeze.\n");
      text.append("A report without an \"End\" section is the one of an Eclipse that was closed or killed while it was frozen.\n");
      text.append(section(elapsedMillis, dump, true));
      report = folder.create(ReportFolder.FREEZE, start, text.toString());
      failed = false;
    } catch (IOException | RuntimeException e) {
      failed(e);
    }
  }

  @Override
  public void observed(long elapsedMillis, boolean allThreads) {
    if (report == null) {
      return;
    }
    try {
      folder.append(report, section(elapsedMillis, allThreads ? probe.allThreads() : probe.uiThread(), allThreads));
    } catch (IOException | RuntimeException e) {
      failed(e);
    }
  }

  /**
   * @return a look at the freeze, as text: what the UI thread does, the counters since the start, then the stacks
   */
  private String section(long elapsedMillis, ThreadDump dump, boolean allThreads) {
    StringBuilder text = new StringBuilder("\n=== +").append(elapsedMillis).append(" ms: ").append(allThreads ? "all the threads" : "the UI thread")
        .append(" ===\n");
    long uiId = probe.uiThreadId();
    Counters counters = probe.counters();
    text.append("Summary: ").append(ThreadDumps.summary(dump, uiId)).append('\n');
    text.append("CPU time of the UI thread since the start: ").append(cpu(counters)).append('\n');
    text.append("Garbage collection since the start: ").append(counters.gcSince(baseline)).append('\n');
    ThreadStack ui = dump.thread(uiId);
    if (allThreads) {
      text.append("Heap: ").append(probe.heap()).append('\n');
      if (!dump.deadlocked().isEmpty()) {
        text.append("Deadlock: ").append(ThreadDumps.deadlock(dump)).append('\n');
      }
      List<String> jobs = probe.jobs();
      text.append("Jobs that run or wait to run:").append(jobs.isEmpty() ? " none" : "").append('\n');
      for (String job : jobs) {
        text.append("  ").append(job).append('\n');
      }
      text.append('\n').append(ThreadDumps.format(dump, uiId));
    } else if (ui != null && ui.sameAs(lastUi)) {
      text.append("Same stack and state of the UI thread as at +").append(lastUiAt).append(" ms.\n");
    } else if (ui != null) {
      text.append('\n').append(ThreadDumps.format(ui, true));
    }
    if (ui != null && !ui.sameAs(lastUi)) {
      lastUi = ui;
      lastUiAt = elapsedMillis;
    }
    return text.toString();
  }

  private String cpu(Counters counters) {
    long cpu = counters.uiCpuSince(baseline);
    return cpu < 0 ? "unknown" : cpu + " ms";
  }

  @Override
  public void ended(long durationMillis, long pausedMillis) {
    lastUi = null;
    if (ignoring || report == null) {
      ignoring = false;
      report = null;
      return;
    }
    Path ended = report;
    report = null;
    try {
      Counters counters = probe.counters();
      StringBuilder text = new StringBuilder("\n=== End ===\n");
      text.append("The UI thread answered again after ").append(durationMillis).append(" ms.\n");
      text.append("CPU time of the UI thread during the freeze: ").append(cpu(counters)).append('\n');
      text.append("Garbage collection during the freeze: ").append(counters.gcSince(baseline)).append('\n');
      if (pausedMillis > 0) {
        text.append("The whole virtual machine was paused during about ").append(pausedMillis).append(" ms of the freeze.\n");
      }
      folder.append(ended, text.toString());
      ended = folder.finish(ended, durationMillis);
      log.warning("The user interface was frozen for " + durationMillis + " ms. Thread dumps: " + ended);
    } catch (IOException | RuntimeException e) {
      failed(e);
    }
  }

  @Override
  public void paused(long millis) {
    try {
      Counters counters = probe.counters();
      if (report != null) {
        folder.append(report, "\nThe whole virtual machine, this watchdog included, was paused during about " + millis
            + " ms. Garbage collection since the start: " + counters.gcSince(baseline) + "\n");
      } else if (!ignoring && baseline != null && counters.gcPauseSince(baseline) * 2 >= millis) {
        // Only a pause that the garbage collector explains: a computer that was suspended is nothing to fix.
        Instant start = now.get().minusMillis(millis);
        StringBuilder text = new StringBuilder("Pause of the whole Java virtual machine of Eclipse, seen by EFreeze\n");
        text.append("Start: about ").append(TIME.withZone(zone).format(start)).append('\n');
        text.append("Duration: about ").append(millis).append(" ms\n");
        text.append("Garbage collection during the pause: ").append(counters.gcSince(baseline)).append('\n');
        text.append("Heap after the pause: ").append(probe.heap()).append('\n');
        text.append("Session: ").append(folder.session(sessionStart, session.get()))
            .append(" (the versions of Eclipse, of Java, of the system and of the plug-ins)\n");
        text.append("Every thread was stopped, the watchdog of EFreeze too: this is not a freeze of the UI thread alone, and the"
            + " stacks of the threads would tell nothing. The memory of Eclipse (-Xmx) or its garbage collector is what to look at.\n");
        Path pause = folder.finish(folder.create(ReportFolder.GC_PAUSE, start, text.toString()), millis);
        log.warning("Eclipse was paused for about " + millis + " ms by its garbage collector. Report: " + pause);
      }
    } catch (IOException | RuntimeException e) {
      failed(e);
    }
  }

  private void failed(Exception e) {
    if (!failed) {
      failed = true;
      log.error("EFreeze could not write the report of a freeze in " + folder.folder(), e);
    }
  }
}
