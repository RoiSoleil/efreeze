package org.eclipse.efreeze.core;

import java.lang.management.ManagementFactory;
import java.util.*;

import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.service.datalocation.Location;
import org.eclipse.swt.SWT;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

/**
 * What the analysis of a freeze needs to find the sources of the frames of its stacks: the versions of Eclipse, of Java, of
 * the system and of every plug-in. Collected once when Eclipse starts, not during a freeze: it asks the framework.
 */
public class SessionInfo {

  private SessionInfo() {
  }

  public static String collect(BundleContext context) {
    StringBuilder text = new StringBuilder("Session of Eclipse watched by EFreeze ").append(context.getBundle().getVersion()).append('\n');
    IProduct product = Platform.getProduct();
    text.append("Product: ").append(product == null ? "unknown" : product.getName() + " (" + product.getId() + ")");
    append(text, ", build ", System.getProperty("eclipse.buildId"));
    append(text, ", application ", System.getProperty("eclipse.application"));
    text.append("\nJava: ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor")).append(", ")
        .append(System.getProperty("java.vm.name")).append(')');
    text.append("\nOptions of the virtual machine: ").append(String.join(" ", vmOptions(ManagementFactory.getRuntimeMXBean().getInputArguments())));
    text.append("\nSystem: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append(' ')
        .append(System.getProperty("os.arch")).append(", ").append(Runtime.getRuntime().availableProcessors())
        .append(" processors, window system ").append(SWT.getPlatform()).append(", SWT ").append(SWT.getVersion());
    text.append("\nInstallation: ").append(location(Platform.getInstallLocation()));
    text.append("\nWorkspace: ").append(location(Platform.getInstanceLocation()));
    text.append("\n\nPlug-ins (symbolic name and version):\n");
    List<String> bundles = new ArrayList<>();
    for (Bundle bundle : context.getBundles()) {
      bundles.add(bundle.getSymbolicName() + ' ' + bundle.getVersion());
    }
    Collections.sort(bundles);
    for (String bundle : bundles) {
      text.append(bundle).append('\n');
    }
    return text.toString();
  }

  /**
   * @return the options that tell how the virtual machine runs (memory, garbage collector...); the system properties are left
   *         out: they may hold a password
   */
  static List<String> vmOptions(List<String> arguments) {
    List<String> options = new ArrayList<>();
    for (String argument : arguments) {
      if (argument.startsWith("-X")) {
        options.add(argument);
      }
    }
    return options;
  }

  private static void append(StringBuilder text, String label, String value) {
    if (value != null && !value.isBlank()) {
      text.append(label).append(value);
    }
  }

  private static String location(Location location) {
    return location == null || location.getURL() == null ? "unknown" : location.getURL().getPath();
  }
}
