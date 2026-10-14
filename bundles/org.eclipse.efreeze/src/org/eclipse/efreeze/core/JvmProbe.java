package org.eclipse.efreeze.core;

import java.lang.management.*;
import java.util.*;

import org.eclipse.efreeze.model.Counters;
import org.eclipse.efreeze.model.Counters.Collector;
import org.eclipse.efreeze.model.ThreadDump;

/**
 * Reads the running Java virtual machine.
 */
public class JvmProbe implements Probe {

  /** How long the job manager may take to list its jobs. */
  private static final long JOBS_TIMEOUT = 500;
  private static final long MEGABYTE = 1024 * 1024;

  private final Thread ui;
  private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();

  public JvmProbe(Thread ui) {
    this.ui = ui;
  }

  @Override
  public long uiThreadId() {
    return ui.threadId();
  }

  @Override
  public ThreadDump allThreads() {
    ThreadInfo[] infos = threads.dumpAllThreads(threads.isObjectMonitorUsageSupported(), threads.isSynchronizerUsageSupported());
    long[] deadlocked = threads.isSynchronizerUsageSupported() ? threads.findDeadlockedThreads() : threads.findMonitorDeadlockedThreads();
    return ThreadDumps.of(infos, deadlocked);
  }

  @Override
  public ThreadDump uiThread() {
    ThreadInfo[] infos = threads.getThreadInfo(new long[] { ui.threadId() }, threads.isObjectMonitorUsageSupported(),
        threads.isSynchronizerUsageSupported());
    return ThreadDumps.of(infos, null);
  }

  @Override
  public Counters counters() {
    long cpu = -1;
    if (threads.isThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled()) {
      long nanos = threads.getThreadCpuTime(ui.threadId());
      cpu = nanos < 0 ? -1 : nanos / 1_000_000;
    }
    Map<String, Collector> collectors = new LinkedHashMap<>();
    for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
      collectors.put(collector.getName(), new Collector(Math.max(0, collector.getCollectionCount()), Math.max(0, collector.getCollectionTime())));
    }
    return new Counters(cpu, collectors);
  }

  @Override
  public List<String> jobs() {
    return Jobs.describe(JOBS_TIMEOUT);
  }

  @Override
  public String heap() {
    MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    return heap.getUsed() / MEGABYTE + " MB used" + (heap.getMax() > 0 ? " of " + heap.getMax() / MEGABYTE + " MB" : "");
  }
}
