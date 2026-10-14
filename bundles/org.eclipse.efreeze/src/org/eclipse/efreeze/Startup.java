package org.eclipse.efreeze;

import org.eclipse.ui.IStartup;

/**
 * Starts to watch the UI thread as soon as the workbench is there, in every workspace.
 */
public class Startup implements IStartup {

  @Override
  public void earlyStartup() {
    Activator activator = Activator.getDefault();
    if (activator != null) {
      activator.startMonitoring();
    }
  }

}
