package org.eclipse.efreeze.core;

import java.util.List;

import org.eclipse.efreeze.model.Counters;
import org.eclipse.efreeze.model.ThreadDump;

/**
 * What a report reads from the running Eclipse. Used by the watchdog while the UI thread is frozen: nothing here may wait for
 * the UI thread, nor for a lock that a frozen Eclipse may hold for ever.
 */
public interface Probe {

  /**
   * @return the id of the UI thread
   */
  long uiThreadId();

  /**
   * @return all the threads, with their locks and the deadlocks
   */
  ThreadDump allThreads();

  /**
   * @return the UI thread only, with its locks
   */
  ThreadDump uiThread();

  Counters counters();

  /**
   * @return the jobs that run or wait to run, one by line
   */
  List<String> jobs();

  /**
   * @return the memory of the heap, in a few words
   */
  String heap();
}
