package org.eclipse.efreeze;

import static org.eclipse.efreeze.utils.PreferenceConstantes.*;

import org.eclipse.core.runtime.preferences.*;

public class PreferenceInitializer extends AbstractPreferenceInitializer {

  public static final int DEFAULT_THRESHOLD = 1000;
  public static final int DEFAULT_MAX_REPORTS = 100;
  public static final String DEFAULT_RELAY = "https://efreeze-relay.efreeze-relay.workers.dev/reports";

  @Override
  public void initializeDefaultPreferences() {
    IEclipsePreferences preferences = DefaultScope.INSTANCE.getNode(Activator.PLUGIN_ID);
    preferences.putBoolean(ENABLED, true);
    preferences.putInt(THRESHOLD, DEFAULT_THRESHOLD);
    preferences.put(FOLDER, "");
    preferences.putInt(MAX_REPORTS, DEFAULT_MAX_REPORTS);
    preferences.putBoolean(SHARE, false);
    preferences.put(RELAY, DEFAULT_RELAY);
  }

}
