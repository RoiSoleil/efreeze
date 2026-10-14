package org.eclipse.efreeze.model;

import java.nio.file.Path;

/**
 * The preferences of the plug-in.
 *
 * @param threshold
 *          the time without an answer of the UI thread that makes a freeze, in milliseconds
 * @param folder
 *          where the reports are written
 * @param maxReports
 *          the number of reports that are kept
 */
public record Settings(boolean enabled, long threshold, Path folder, int maxReports) {
}
