package org.eclipse.efreeze;

import static org.eclipse.efreeze.utils.PreferenceConstantes.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.widgets.Display;

/**
 * The workbench of the tests, whose UI thread is the thread of the tests: a test that does not handle the events freezes it.
 */
public class TestWorkbench {

  /** A threshold that keeps the tests short. */
  public static final int THRESHOLD_OF_THE_TESTS = 300;

  /**
   * Watches the UI thread with a short threshold, and reports in this folder.
   */
  public static void watch(Path folder) {
    IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID);
    preferences.putBoolean(ENABLED, true);
    preferences.putInt(THRESHOLD, THRESHOLD_OF_THE_TESTS);
    preferences.put(FOLDER, folder.toString());
    Activator.getDefault().startMonitoring();
    // The UI thread has answered once: the watchdog is at work.
    waitFor(() -> false, 2 * THRESHOLD_OF_THE_TESTS);
  }

  /**
   * Back to the preferences of a new workspace.
   */
  public static void restorePreferences() {
    IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID);
    for (String preference : List.of(ENABLED, THRESHOLD, FOLDER, MAX_REPORTS)) {
      preferences.remove(preference);
    }
    Activator.getDefault().startMonitoring();
  }

  /**
   * Runs the events of the display until the condition is true.
   */
  public static boolean waitFor(BooleanSupplier condition, long timeout) {
    Display display = Display.getCurrent();
    long end = System.currentTimeMillis() + timeout;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > end) {
        return false;
      }
      if (!display.readAndDispatch()) {
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * @return the names of the reports of the folder: the freezes and the pauses, not the sessions
   */
  public static List<String> reports(Path folder) {
    if (!Files.exists(folder)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(folder)) {
      return files.map(file -> file.getFileName().toString()).filter(name -> !name.startsWith("session-")).sorted().toList();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
