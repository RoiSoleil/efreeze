package org.eclipse.efreeze.utils;

public class PreferenceConstantes {

  /** Whether the freezes of the user interface are detected. */
  public static final String ENABLED = "ENABLED";
  /** The time without an answer of the UI thread that makes a freeze, in milliseconds. */
  public static final String THRESHOLD = "THRESHOLD";
  /** The folder of the reports; empty for the folder of the plug-in in the metadata of the workspace. */
  public static final String FOLDER = "FOLDER";
  /** The number of reports that are kept: the oldest ones are deleted. */
  public static final String MAX_REPORTS = "MAX_REPORTS";

  public static final int MIN_THRESHOLD = 100;
  public static final int MAX_THRESHOLD = 600_000;

}
