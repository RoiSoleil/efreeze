package org.eclipse.efreeze;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;

import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.Test;

class StartupTest {

  @Test
  void watchdogStartsWithTheWorkbench() {
    // The workbench creates the class named here once it has started.
    assertTrue(Arrays.stream(Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.startup"))
        .anyMatch(element -> Startup.class.getName().equals(element.getAttribute("class"))
            && Activator.PLUGIN_ID.equals(element.getContributor().getName())));
    Activator.getDefault().stopMonitoring();
    assertNull(Activator.getDefault().getMonitor());
    new Startup().earlyStartup();
    FreezeMonitor monitor = Activator.getDefault().getMonitor();
    assertNotNull(monitor);
    assertTrue(monitor.isWatching());
    assertFalse(monitor.isFrozen());
    // By default: a freeze is one second, and the reports are in the metadata of the workspace.
    assertEquals(1000, monitor.settings().threshold());
    assertEquals(100, monitor.settings().maxReports());
    assertEquals(Platform.getStateLocation(Activator.getDefault().getBundle()).toPath(), monitor.folder());
    assertTrue(monitor.folder().endsWith(".metadata/.plugins/org.eclipse.efreeze"), monitor.folder().toString());
  }
}
