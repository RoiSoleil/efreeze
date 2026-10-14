package org.eclipse.efreeze.model;

import java.net.URI;
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
 * @param relay
 *          where the reports of the freezes are sent to be shared in public issues on GitHub, or {@code null} if they are not
 *          shared
 */
public record Settings(boolean enabled, long threshold, Path folder, int maxReports, URI relay) {
}
