package org.eclipse.efreeze.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.eclipse.efreeze.Activator;
import org.junit.jupiter.api.Test;

class SessionInfoTest {

  @Test
  void tellsTheVersionsThatLeadToTheSourcesOfAStack() {
    String session = SessionInfo.collect(Activator.getDefault().getBundle().getBundleContext());
    String[] lines = session.split("\n");
    assertTrue(lines[0].matches("Session of Eclipse watched by EFreeze 0\\.1\\.0\\..+"), lines[0]);
    assertTrue(lines[1].startsWith("Product: "), lines[1]);
    assertTrue(lines[1].contains(", application org.eclipse."), lines[1]);
    assertEquals("Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ", "
        + System.getProperty("java.vm.name") + ")", lines[2]);
    assertTrue(lines[3].startsWith("Options of the virtual machine: "), lines[3]);
    assertFalse(lines[3].contains("-D"), lines[3]);
    assertTrue(lines[4].matches("System: .+, \\d+ processors, window system (gtk|win32|cocoa), SWT \\d+"), lines[4]);
    assertTrue(lines[5].startsWith("Installation: /") || lines[5].matches("Installation: /?[A-Za-z]:.*"), lines[5]);
    assertTrue(lines[6].startsWith("Workspace: "), lines[6]);
    assertEquals("Plug-ins (symbolic name and version):", lines[8]);
    List<String> plugins = List.of(lines).subList(9, lines.length);
    assertTrue(plugins.stream().anyMatch(plugin -> plugin.matches("org\\.eclipse\\.efreeze 0\\.1\\.0\\..+")), "the plug-in itself");
    assertTrue(plugins.stream().anyMatch(plugin -> plugin.matches("org\\.eclipse\\.swt \\d+\\.\\d+\\.\\d+\\..+")), "SWT, whose frames are in every stack");
    assertTrue(plugins.size() > 50, "every plug-in of the installation");
    assertEquals(plugins.stream().sorted().toList(), plugins);
  }

  @Test
  void systemPropertiesAreLeftOutOfTheOptions() {
    assertEquals(List.of("-Xmx2g", "-XX:+UseG1GC", "-Xss4m"),
        SessionInfo.vmOptions(List.of("-Xmx2g", "-Dhttp.proxyPassword=secret", "-XX:+UseG1GC", "--add-opens=java.base/java.lang=ALL-UNNAMED", "-Xss4m")));
  }
}
