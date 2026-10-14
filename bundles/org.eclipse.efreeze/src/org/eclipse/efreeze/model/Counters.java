package org.eclipse.efreeze.model;

import java.util.Map;
import java.util.StringJoiner;

/**
 * Counters of the Java virtual machine, which only grow: two readings tell what a freeze was made of.
 *
 * @param uiCpuMillis
 *          the processor time that the UI thread has used, or -1 if the virtual machine does not tell it
 * @param collectors
 *          the garbage collectors, by name
 */
public record Counters(long uiCpuMillis, Map<String, Collector> collectors) {

  /**
   * A garbage collector.
   *
   * @param millis
   *          the time it has spent collecting
   */
  public record Collector(long count, long millis) {
  }

  /**
   * @return the processor time used by the UI thread since an earlier reading, or -1 if it is not known
   */
  public long uiCpuSince(Counters before) {
    return uiCpuMillis < 0 || before == null || before.uiCpuMillis < 0 ? -1 : Math.max(0, uiCpuMillis - before.uiCpuMillis);
  }

  /**
   * @return the time that the collectors which stop every thread have spent since an earlier reading
   */
  public long gcPauseSince(Counters before) {
    long millis = 0;
    for (Map.Entry<String, Collector> collector : collectors.entrySet()) {
      if (stopsTheThreads(collector.getKey())) {
        millis += since(before, collector.getKey()).millis();
      }
    }
    return millis;
  }

  /**
   * @return the collections since an earlier reading, for a report: {@code 120 ms (G1 Young Generation: 3 collections, 120 ms)}
   */
  public String gcSince(Counters before) {
    StringJoiner details = new StringJoiner("; ");
    for (String name : collectors.keySet()) {
      Collector delta = since(before, name);
      if (delta.count() > 0 || delta.millis() > 0) {
        details.add(name + ": " + delta.count() + (delta.count() == 1 ? " collection, " : " collections, ") + delta.millis() + " ms"
            + (stopsTheThreads(name) ? "" : ", beside the threads"));
      }
    }
    return details.length() == 0 ? "none" : gcPauseSince(before) + " ms (" + details + ")";
  }

  private Collector since(Counters before, String name) {
    Collector now = collectors.get(name);
    Collector then = before == null ? null : before.collectors.get(name);
    return then == null ? now : new Collector(Math.max(0, now.count() - then.count()), Math.max(0, now.millis() - then.millis()));
  }

  /**
   * The concurrent collectors (the cycles of G1, ZGC and Shenandoah) work beside the threads: their time is not a pause.
   */
  private static boolean stopsTheThreads(String collector) {
    return !collector.contains("Concurrent") && !collector.contains("Cycles");
  }
}
