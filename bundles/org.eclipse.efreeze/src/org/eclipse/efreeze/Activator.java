package org.eclipse.efreeze;

import static org.eclipse.efreeze.utils.PreferenceConstantes.*;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.IPreferencesService;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.efreeze.core.SessionInfo;
import org.eclipse.efreeze.model.Settings;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class Activator extends AbstractUIPlugin {

  // The plug-in ID
  public static final String PLUGIN_ID = "org.eclipse.efreeze"; //$NON-NLS-1$

  // The shared instance
  private static Activator plugin;

  private FreezeMonitor monitor;
  /** When Eclipse started to be watched, and its versions: the same for every report of this session. */
  private Instant sessionStart;
  private String session;
  /** A new preference is used at once. */
  private final IPreferenceChangeListener preferenceListener = event -> {
    if (isMonitoringStarted()) {
      startMonitoring();
    }
  };
  private boolean monitoringStarted;

  /**
   * The constructor
   */
  public Activator() {
  }

  @Override
  public void start(BundleContext context) throws Exception {
    super.start(context);
    plugin = this;
    InstanceScope.INSTANCE.getNode(PLUGIN_ID).addPreferenceChangeListener(preferenceListener);
  }

  @Override
  public void stop(BundleContext context) throws Exception {
    InstanceScope.INSTANCE.getNode(PLUGIN_ID).removePreferenceChangeListener(preferenceListener);
    stopMonitoring();
    plugin = null;
    super.stop(context);
  }

  /**
   * Returns the shared instance
   *
   * @return the shared instance
   */
  public static Activator getDefault() {
    return plugin;
  }

  public static void error(Throwable t) {
    getDefault().getLog().error(t.getLocalizedMessage(), t);
  }

  /**
   * Starts to watch the UI thread with the preferences of the moment, or stops if they say so. Called when the workbench
   * starts, then at each change of the preferences.
   */
  public synchronized void startMonitoring() {
    stopMonitoring();
    monitoringStarted = true;
    Settings settings = settings();
    if (!settings.enabled() || !PlatformUI.isWorkbenchRunning()) {
      return;
    }
    if (session == null) {
      sessionStart = Instant.now();
      session = SessionInfo.collect(getBundle().getBundleContext());
    }
    String versions = session;
    monitor = new FreezeMonitor(PlatformUI.getWorkbench().getDisplay(), settings, sessionStart, () -> versions, getLog());
    monitor.start();
  }

  public synchronized void stopMonitoring() {
    if (monitor != null) {
      monitor.stop();
      monitor = null;
    }
  }

  private synchronized boolean isMonitoringStarted() {
    return monitoringStarted;
  }

  /**
   * @return the watchdog of the UI thread, or {@code null} if it does not run
   */
  public synchronized FreezeMonitor getMonitor() {
    return monitor;
  }

  /**
   * @return the preferences of the plug-in
   */
  public Settings settings() {
    IPreferencesService preferences = Platform.getPreferencesService();
    int threshold = preferences.getInt(PLUGIN_ID, THRESHOLD, PreferenceInitializer.DEFAULT_THRESHOLD, null);
    int maxReports = preferences.getInt(PLUGIN_ID, MAX_REPORTS, PreferenceInitializer.DEFAULT_MAX_REPORTS, null);
    return new Settings(preferences.getBoolean(PLUGIN_ID, ENABLED, true, null), Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, threshold)),
        folder(preferences.getString(PLUGIN_ID, FOLDER, "", null)), Math.max(1, maxReports));
  }

  /**
   * @param preference
   *          the folder chosen by the user, or nothing
   * @return the folder of the reports: by default the one of the plug-in in the metadata of the workspace
   */
  public Path folder(String preference) {
    String chosen = preference == null ? "" : preference.trim();
    if (!chosen.isEmpty()) {
      try {
        return Path.of(chosen).toAbsolutePath();
      } catch (InvalidPathException e) {
        // The default folder then.
      }
    }
    return getStateLocation().toPath();
  }
}
