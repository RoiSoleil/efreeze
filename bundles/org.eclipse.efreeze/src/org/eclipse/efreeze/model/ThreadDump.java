package org.eclipse.efreeze.model;

import java.util.List;

/**
 * The threads of the Java virtual machine at a given time: all of them, or only the UI thread.
 *
 * @param deadlocked
 *          the ids of the threads that wait for each other for ever
 */
public record ThreadDump(List<ThreadStack> threads, List<Long> deadlocked) {

  /**
   * @return the thread of this id, or {@code null} if it is not in the dump
   */
  public ThreadStack thread(long id) {
    for (ThreadStack thread : threads) {
      if (thread.id() == id) {
        return thread;
      }
    }
    return null;
  }
}
