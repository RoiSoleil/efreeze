package org.eclipse.efreeze;

import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Supplier;

import org.eclipse.core.runtime.ILog;
import org.eclipse.efreeze.core.*;
import org.eclipse.efreeze.model.Settings;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

/**
 * The watchdog of the UI thread of the workbench: a thread of its own, which never waits for the UI thread.
 */
public class FreezeMonitor {

  private final Display display;
  private final Settings settings;
  private final FreezeDetector detector;
  private final FreezeReporter reporter;
  private final Thread thread;
  private volatile boolean stopped;

  /**
   * @param sessionStart
   *          when Eclipse started to be watched
   * @param session
   *          the versions of Eclipse and of its plug-ins, for the reports
   */
  public FreezeMonitor(Display display, Settings settings, Instant sessionStart, Supplier<String> session, ILog log) {
    this.display = display;
    this.settings = settings;
    reporter = new FreezeReporter(new ReportFolder(settings.folder(), settings.maxReports()), new JvmProbe(display.getThread()),
        settings.threshold(), sessionStart, session, Instant::now, FreezeMonitor::isClosing, new FreezeReporter.Log() {
          @Override
          public void warning(String message) {
            log.warn(message);
          }

          @Override
          public void error(String message, Throwable exception) {
            log.error(message, exception);
          }
        });
    detector = new FreezeDetector(this::post, System::nanoTime, settings.threshold(), reporter);
    thread = new Thread(this::watch, "EFreeze watchdog");
    thread.setDaemon(true);
    // Above the threads that keep the processors busy: a watchdog that does not run sees nothing.
    thread.setPriority(Thread.MAX_PRIORITY);
  }

  public Settings settings() {
    return settings;
  }

  /**
   * @return the folder of the reports
   */
  public Path folder() {
    return settings.folder();
  }

  /**
   * @return {@code true} while the UI thread is frozen
   */
  public boolean isFrozen() {
    return detector.isFrozen();
  }

  /**
   * @return {@code true} while the watchdog runs
   */
  public boolean isWatching() {
    return thread.isAlive();
  }

  public void start() {
    thread.start();
  }

  /**
   * Stops the watchdog at its next look: it is not interrupted, it may be writing a report.
   */
  public void stop() {
    stopped = true;
  }

  private void watch() {
    try {
      while (!stopped && detector.tick()) {
        Thread.sleep(detector.tickMillis());
      }
    } catch (InterruptedException e) {
      // Eclipse is closing.
    }
  }

  private boolean post(Runnable task) {
    try {
      if (stopped || display.isDisposed()) {
        return false;
      }
      display.asyncExec(task);
      return true;
    } catch (SWTException e) {
      // The display was disposed meanwhile.
      return false;
    }
  }

  /** An Eclipse that closes saves its state in the UI thread, and does not handle its events any more. */
  private static boolean isClosing() {
    return !PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing();
  }
}
