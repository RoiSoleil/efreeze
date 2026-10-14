package org.eclipse.efreeze.model;

import java.util.List;

/**
 * A thread at the time of a thread dump.
 *
 * @param frames
 *          its stack, the frame that runs first
 * @param lock
 *          the lock or the condition that the thread waits for, or {@code null}
 * @param lockOwner
 *          the id of the thread that holds this lock, or -1
 * @param lockOwnerName
 *          the name of that thread, or {@code null}
 * @param monitors
 *          the monitors that the thread holds
 * @param synchronizers
 *          the other locks that it holds, such as a ReentrantLock
 */
public record ThreadStack(long id, String name, Thread.State state, boolean daemon, int priority, boolean inNative, boolean suspended,
    List<StackTraceElement> frames, String lock, long lockOwner, String lockOwnerName, List<Monitor> monitors, List<String> synchronizers) {

  /**
   * A monitor held by a thread.
   *
   * @param depth
   *          the frame of the stack that locked it, or -1
   */
  public record Monitor(String lock, int depth) {
  }

  /**
   * @return {@code true} if the other thread is in the same place, in the same state
   */
  public boolean sameAs(ThreadStack other) {
    return other != null && state == other.state && frames.equals(other.frames) && java.util.Objects.equals(lock, other.lock)
        && lockOwner == other.lockOwner;
  }
}
