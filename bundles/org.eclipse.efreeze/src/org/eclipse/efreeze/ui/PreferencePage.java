package org.eclipse.efreeze.ui;

import static org.eclipse.efreeze.utils.PreferenceConstantes.*;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.efreeze.Activator;
import org.eclipse.jface.preference.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

public class PreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

  public static final String ID = "org.eclipse.efreeze.PreferencePage";

  private DirectoryFieldEditor folderEditor;
  private Label folderLabel;

  public PreferencePage() {
    super(GRID);
    setDescription("When the user interface of Eclipse does not answer, the stacks of its threads are saved in a file.");
  }

  @Override
  protected void createFieldEditors() {
    Composite parent = getFieldEditorParent();
    addField(new BooleanFieldEditor(ENABLED, "&Detect the freezes of the user interface", parent));
    IntegerFieldEditor thresholdEditor = new IntegerFieldEditor(THRESHOLD, "&Time without an answer that makes a freeze (ms) : ", parent, 6);
    thresholdEditor.setValidRange(MIN_THRESHOLD, MAX_THRESHOLD);
    addField(thresholdEditor);
    folderEditor = new DirectoryFieldEditor(FOLDER, "&Folder of the thread dumps : ", parent);
    folderEditor.setEmptyStringAllowed(true);
    folderEditor.getTextControl(parent).setMessage("In the metadata of the workspace if empty");
    folderEditor.getTextControl(parent).addModifyListener(e -> updateFolder());
    addField(folderEditor);
    folderLabel = new Label(parent, SWT.NONE);
    folderLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
    IntegerFieldEditor maxReportsEditor = new IntegerFieldEditor(MAX_REPORTS, "&Number of reports that are kept : ", parent, 6);
    maxReportsEditor.setValidRange(1, 100_000);
    addField(maxReportsEditor);
    Link link = new Link(parent, SWT.NONE);
    link.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
    link.setText("<A>Open the folder of the thread dumps</A>");
    link.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> Program.launch(folder())));
  }

  @Override
  protected void initialize() {
    super.initialize();
    updateFolder();
  }

  /**
   * @return the folder of the reports, as the page shows it
   */
  String folder() {
    return Activator.getDefault().folder(folderEditor.getStringValue()).toString();
  }

  private void updateFolder() {
    if (folderLabel != null) {
      folderLabel.setText(folder());
    }
  }

  @Override
  public void init(IWorkbench workbench) {
    setPreferenceStore(new ScopedPreferenceStore(InstanceScope.INSTANCE, Activator.PLUGIN_ID));
  }

}
