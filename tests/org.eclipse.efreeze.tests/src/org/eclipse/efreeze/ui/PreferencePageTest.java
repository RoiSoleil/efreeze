package org.eclipse.efreeze.ui;

import static org.eclipse.efreeze.TestWorkbench.*;
import static org.eclipse.efreeze.utils.PreferenceConstantes.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.function.Predicate;

import org.eclipse.core.runtime.Platform;
import org.eclipse.efreeze.Activator;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreferencePageTest {

  @TempDir
  Path folder;

  @AfterEach
  void restore() {
    restorePreferences();
  }

  private static <T extends Control> T find(Control control, Class<T> type, Predicate<T> condition) {
    if (type.isInstance(control) && condition.test(type.cast(control))) {
      return type.cast(control);
    }
    if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        T found = find(child, type, condition);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  /** Types in a field: its value is checked when a key is released. */
  private static void type(Text field, String text) {
    field.setText(text);
    field.notifyListeners(SWT.KeyUp, new Event());
  }

  @Test
  void pageShowsThePreferencesAndTheWatchdogUsesThemAtOnce() {
    Activator.getDefault().startMonitoring();
    PreferenceDialog dialog = PreferencesUtil.createPreferenceDialogOn(PlatformUI.getWorkbench().getWorkbenchWindows()[0].getShell(),
        PreferencePage.ID, new String[] { PreferencePage.ID }, null);
    dialog.setBlockOnOpen(false);
    dialog.open();
    try {
      PreferencePage page = (PreferencePage) dialog.getSelectedPage();
      Control control = page.getControl();
      Button enabled = find(control, Button.class, button -> button.getText().contains("Detect the freezes"));
      assertTrue(enabled.getSelection(), "watching by default");
      Text threshold = find(control, Text.class, text -> "1000".equals(text.getText()));
      assertNotNull(threshold, "one second by default");
      Text reports = find(control, Text.class, text -> "100".equals(text.getText()));
      assertNotNull(reports);
      Text path = find(control, Text.class, text -> "In the metadata of the workspace if empty".equals(text.getMessage()));
      assertEquals("", path.getText());
      String defaultFolder = Platform.getStateLocation(Activator.getDefault().getBundle()).toPath().toString();
      assertNotNull(find(control, Label.class, label -> defaultFolder.equals(label.getText())), "where the reports are");
      assertEquals(defaultFolder, page.folder());
      assertNotNull(find(control, Link.class, link -> link.getText().contains("Open the folder")));

      // Another folder is shown at once.
      path.setText(folder.toString());
      assertNotNull(find(control, Label.class, label -> folder.toString().equals(label.getText())));
      // A threshold that is too short is refused.
      type(threshold, "50");
      assertFalse(page.isValid());
      type(threshold, "2000");
      assertTrue(page.isValid());
      type(reports, "7");
      assertTrue(page.performOk());
    } finally {
      dialog.close();
    }
    assertEquals(2000, Platform.getPreferencesService().getInt(Activator.PLUGIN_ID, THRESHOLD, 0, null));
    assertEquals(2000, Activator.getDefault().getMonitor().settings().threshold(), "the watchdog started again with it");
    assertEquals(7, Activator.getDefault().getMonitor().settings().maxReports());
    assertEquals(folder, Activator.getDefault().getMonitor().folder());
    waitFor(() -> false, 100);
  }
}
