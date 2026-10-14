package org.eclipse.efreeze;

import static org.eclipse.efreeze.TestWorkbench.*;
import static org.eclipse.efreeze.utils.PreferenceConstantes.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real freezes of the UI thread of the workbench of the tests, which is the thread of the tests.
 */
class FreezeMonitorTest {

  private static final Pattern FINISHED = Pattern.compile("freeze-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.\\d{3}-(\\d+)ms\\.txt");

  @TempDir
  Path folder;
  private final List<IStatus> logged = new ArrayList<>();
  private final ILogListener logListener = (status, plugin) -> {
    if (Activator.PLUGIN_ID.equals(status.getPlugin())) {
      synchronized (logged) {
        logged.add(status);
      }
    }
  };

  @BeforeEach
  void watchTheUiThread() {
    Platform.addLogListener(logListener);
    watch(folder);
  }

  @AfterEach
  void restore() {
    restorePreferences();
    Platform.removeLogListener(logListener);
  }

  /** Handles the events until the UI thread has answered and the report of the freeze is finished. */
  private String finishedReport() throws IOException {
    assertTrue(waitFor(() -> reports(folder).stream().anyMatch(name -> FINISHED.matcher(name).matches()), 10_000),
        "No finished report in " + reports(folder));
    assertEquals(1, reports(folder).size(), reports(folder).toString());
    return Files.readString(folder.resolve(reports(folder).get(0)));
  }

  private long durationOfTheReport() {
    Matcher matcher = FINISHED.matcher(reports(folder).get(0));
    assertTrue(matcher.matches());
    return Long.parseLong(matcher.group(1));
  }

  @Test
  void uiThreadThatSleepsIsAFreezeWithItsThreadDumps() throws Exception {
    assertEquals(List.of(), reports(folder));
    Thread.sleep(1500);

    // The report is there while the UI thread has not answered yet.
    List<String> reports = reports(folder);
    assertEquals(1, reports.size(), reports.toString());
    assertTrue(reports.get(0).matches("freeze-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.\\d{3}\\.txt"), reports.get(0));
    assertTrue(Activator.getDefault().getMonitor().isFrozen());
    String frozen = Files.readString(folder.resolve(reports.get(0)));
    assertTrue(frozen.startsWith("UI freeze of Eclipse, seen by EFreeze\nStart: "), frozen);
    assertTrue(frozen.contains(" ms without an answer of the UI thread (threshold: 300 ms)\n"), frozen);
    Matcher first = Pattern.compile("=== \\+(\\d+) ms: all the threads ===\nSummary: The UI thread is TIMED_WAITING in [^\n]*java\\.lang\\.Thread\\.sleep")
        .matcher(frozen);
    assertTrue(first.find(), frozen);
    assertTrue(frozen.contains(", called from org.eclipse.efreeze.FreezeMonitorTest.uiThreadThatSleepsIsAFreezeWithItsThreadDumps(FreezeMonitorTest.java:"),
        "the summary names the code that sleeps: " + frozen);
    long detected = Long.parseLong(first.group(1));
    assertTrue(detected >= 300 && detected < 900, "detected soon after the threshold: " + detected);
    assertTrue(frozen.contains("\nHeap: "), frozen);
    assertTrue(frozen.contains("\nJobs that run or wait to run:"), frozen);
    // The whole stack of the UI thread, down to this test, then the other threads.
    String uiThread = frozen.substring(frozen.indexOf("\n\n\"") + 2);
    assertTrue(uiThread.startsWith("\"" + Thread.currentThread().getName() + "\" #" + Thread.currentThread().threadId() + " "), uiThread);
    String header = uiThread.substring(0, uiThread.indexOf('\n'));
    assertTrue(header.contains(" TIMED_WAITING ") && header.endsWith(" (UI thread)"), header);
    assertTrue(uiThread.contains("\tat org.eclipse.efreeze.FreezeMonitorTest.uiThreadThatSleepsIsAFreezeWithItsThreadDumps(FreezeMonitorTest.java:"), uiThread);
    assertTrue(uiThread.contains("\n\n\"EFreeze watchdog\" #"), "all the threads: " + uiThread);
    // While it lasts, the UI thread is looked at again: it has not moved.
    assertTrue(frozen.contains(" ms: the UI thread ===\n"), frozen);
    assertTrue(frozen.contains("\nSame stack and state of the UI thread as at +" + detected + " ms.\n"), frozen);
    assertFalse(frozen.contains("=== End ==="), frozen);
    synchronized (logged) {
      assertEquals(List.of(), logged, "nothing in the log while Eclipse is frozen");
    }

    // The UI thread handles its events again: the end of the freeze.
    String finished = finishedReport();
    assertFalse(Activator.getDefault().getMonitor().isFrozen());
    assertTrue(finished.startsWith(frozen), finished);
    long duration = durationOfTheReport();
    // The UI thread was asked last at most one look of the watchdog after it fell asleep.
    assertTrue(duration >= 1300 && duration < 4000, "the time the UI thread slept: " + duration);
    assertTrue(finished.contains("\n=== End ===\nThe UI thread answered again after " + duration + " ms.\nCPU time of the UI thread during the freeze: "),
        finished);
    assertTrue(finished.contains("\nGarbage collection during the freeze: "), finished);

    // The versions of this Eclipse, for whoever reads the stacks.
    Matcher session = Pattern.compile("\nSession: (session-[^ ]+\\.txt) ").matcher(finished);
    assertTrue(session.find(), finished);
    String versions = Files.readString(folder.resolve(session.group(1)));
    assertTrue(versions.startsWith("Session of Eclipse watched by EFreeze 0.1.0"), versions);
    assertTrue(versions.contains("\norg.eclipse.efreeze 0.1.0"), versions);

    // And a line in the log of Eclipse, once it answers.
    assertTrue(waitFor(() -> {
      synchronized (logged) {
        return !logged.isEmpty();
      }
    }, 5000));
    synchronized (logged) {
      assertEquals(1, logged.size());
      assertEquals(IStatus.WARNING, logged.get(0).getSeverity());
      assertEquals("The user interface was frozen for " + duration + " ms. Thread dumps: " + folder.resolve(reports(folder).get(0)),
          logged.get(0).getMessage());
    }
  }

  @Test
  void uiThreadThatWaitsForALockIsToldWithTheThreadThatHoldsIt() throws Exception {
    Object lock = new Object();
    CountDownLatch held = new CountDownLatch(1);
    Thread holder = new Thread(() -> {
      synchronized (lock) {
        held.countDown();
        try {
          Thread.sleep(1200);
        } catch (InterruptedException e) {
          // The test is over.
        }
      }
    }, "slow job of the test");
    holder.start();
    assertTrue(held.await(10, TimeUnit.SECONDS));
    synchronized (lock) {
      lock.hashCode();
    }
    String report = finishedReport();
    assertTrue(Pattern.compile("\nSummary: The UI thread is BLOCKED, it waits for <java\\.lang\\.Object@[0-9a-f]+> held by \"slow job of the test\" #"
        + holder.threadId() + ", which is TIMED_WAITING in [^\n]*java\\.lang\\.Thread\\.sleep").matcher(report).find(), report);
    assertTrue(report.contains("\t- waiting to lock <java.lang.Object@"), report);
    assertTrue(report.contains("\n\"slow job of the test\" #" + holder.threadId() + " prio="), report);
    assertTrue(report.contains("\t- locked <java.lang.Object@"), report);
    // A UI thread that waits does not use its processor.
    Matcher cpu = Pattern.compile("CPU time of the UI thread during the freeze: (\\d+) ms").matcher(report);
    assertTrue(cpu.find(), report);
    assertTrue(Long.parseLong(cpu.group(1)) < 300, report);
    holder.join(10_000);
  }

  @Test
  void uiThreadThatComputesUsesItsProcessor() throws Exception {
    long end = System.nanoTime() + 1_000_000_000L;
    long sum = 0;
    while (System.nanoTime() < end) {
      sum += Long.numberOfTrailingZeros(sum + end);
    }
    String report = finishedReport();
    assertTrue(report.contains("Summary: The UI thread is RUNNABLE in "), report + sum);
    Matcher cpu = Pattern.compile("CPU time of the UI thread during the freeze: (\\d+) ms").matcher(report);
    assertTrue(cpu.find(), report);
    assertTrue(Long.parseLong(cpu.group(1)) >= 500, "about a second of processor: " + report);
  }

  @Test
  void workbenchAtRestAndOpenDialogAreNoFreeze() {
    // The UI thread handles its events, with nothing to do.
    waitFor(() -> false, 1000);
    assertEquals(List.of(), reports(folder));

    // A dialog has an event loop of its own: the UI thread answers while the dialog is open.
    Display display = Display.getCurrent();
    MessageDialog dialog = new MessageDialog(PlatformUI.getWorkbench().getWorkbenchWindows()[0].getShell(), "EFreeze", null,
        "A dialog that stays open is no freeze.", MessageDialog.INFORMATION, new String[] { "OK" }, 0);
    display.timerExec(1000, dialog::close);
    long opened = System.currentTimeMillis();
    dialog.open();
    assertTrue(System.currentTimeMillis() - opened >= 900, "the dialog stayed open");
    waitFor(() -> false, 2 * THRESHOLD_OF_THE_TESTS);
    assertEquals(List.of(), reports(folder));
    assertFalse(Activator.getDefault().getMonitor().isFrozen());
    synchronized (logged) {
      assertEquals(List.of(), logged);
    }
  }

  @Test
  void preferencesTurnTheWatchdogOffAndOn() throws Exception {
    FreezeMonitor watching = Activator.getDefault().getMonitor();
    assertEquals(THRESHOLD_OF_THE_TESTS, watching.settings().threshold());
    assertEquals(folder, watching.folder());

    InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID).putBoolean(ENABLED, false);
    assertNull(Activator.getDefault().getMonitor(), "stopped at once");
    assertTrue(waitFor(() -> !watching.isWatching(), 5000), "the thread of the watchdog ends");
    Thread.sleep(3 * THRESHOLD_OF_THE_TESTS);
    waitFor(() -> false, 2 * THRESHOLD_OF_THE_TESTS);
    assertEquals(List.of(), reports(folder), "nothing is watched");

    // On again, with a higher threshold and only one report kept.
    InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID).putInt(THRESHOLD, 600);
    InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID).putInt(MAX_REPORTS, 1);
    InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID).putBoolean(ENABLED, true);
    FreezeMonitor again = Activator.getDefault().getMonitor();
    assertNotSame(watching, again);
    assertEquals(600, again.settings().threshold());
    assertEquals(1, again.settings().maxReports());
    waitFor(() -> false, 400);
    Thread.sleep(300);
    waitFor(() -> false, 400);
    assertEquals(List.of(), reports(folder), "shorter than the threshold");
    Thread.sleep(900);
    finishedReport();
    String first = reports(folder).get(0);
    Thread.sleep(900);
    assertTrue(waitFor(() -> reports(folder).size() == 1 && !reports(folder).get(0).equals(first)
        && FINISHED.matcher(reports(folder).get(0)).matches(), 10_000), "the older report is deleted: " + reports(folder));

    // A threshold that is out of the limits, or a folder that is no path, is not used as it is.
    InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID).putInt(THRESHOLD, 1);
    InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID).put(FOLDER, "no\0path");
    assertEquals(MIN_THRESHOLD, Activator.getDefault().getMonitor().settings().threshold());
    Path defaultFolder = Platform.getStateLocation(Activator.getDefault().getBundle()).toPath();
    assertEquals(defaultFolder, Activator.getDefault().getMonitor().folder());
    InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID).put(FOLDER, "  ");
    assertEquals(defaultFolder, Activator.getDefault().getMonitor().folder());
  }
}
