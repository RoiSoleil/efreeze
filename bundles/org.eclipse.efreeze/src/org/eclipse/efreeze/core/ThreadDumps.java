package org.eclipse.efreeze.core;

import java.lang.management.*;
import java.util.*;

import org.eclipse.efreeze.model.ThreadDump;
import org.eclipse.efreeze.model.ThreadStack;
import org.eclipse.efreeze.model.ThreadStack.Monitor;

/**
 * Takes the thread dumps and writes them as text, close to what jstack prints.
 */
public class ThreadDumps {

  /** How many threads are followed from the UI thread to the one that makes it wait. */
  private static final int MAX_OWNERS = 8;

  private ThreadDumps() {
  }

  /**
   * @param infos
   *          the threads as the virtual machine gives them; one that has ended meanwhile is {@code null}
   * @param deadlocked
   *          the ids of the threads in a deadlock, or {@code null}
   */
  public static ThreadDump of(ThreadInfo[] infos, long[] deadlocked) {
    List<ThreadStack> threads = new ArrayList<>();
    for (ThreadInfo info : infos) {
      if (info != null) {
        threads.add(of(info));
      }
    }
    List<Long> ids = new ArrayList<>();
    if (deadlocked != null) {
      for (long id : deadlocked) {
        ids.add(id);
      }
    }
    return new ThreadDump(threads, ids);
  }

  private static ThreadStack of(ThreadInfo info) {
    List<Monitor> monitors = new ArrayList<>();
    for (MonitorInfo monitor : info.getLockedMonitors()) {
      monitors.add(new Monitor(monitor.toString(), monitor.getLockedStackDepth()));
    }
    List<String> synchronizers = new ArrayList<>();
    for (LockInfo synchronizer : info.getLockedSynchronizers()) {
      synchronizers.add(synchronizer.toString());
    }
    return new ThreadStack(info.getThreadId(), info.getThreadName(), info.getThreadState(), info.isDaemon(), info.getPriority(),
        info.isInNative(), info.isSuspended(), List.of(info.getStackTrace()), info.getLockName(), info.getLockOwnerId(),
        info.getLockOwnerName(), monitors, synchronizers);
  }

  /**
   * @return the threads of the dump as text, the UI thread first
   */
  public static String format(ThreadDump dump, long uiThreadId) {
    StringBuilder text = new StringBuilder();
    ThreadStack ui = dump.thread(uiThreadId);
    if (ui != null) {
      text.append(format(ui, true));
    }
    for (ThreadStack thread : dump.threads()) {
      if (thread != ui) {
        text.append('\n').append(format(thread, false));
      }
    }
    return text.toString();
  }

  /**
   * @return the thread as text: its name and state, then its whole stack with the locks it holds and the one it waits for
   */
  public static String format(ThreadStack thread, boolean ui) {
    StringBuilder text = new StringBuilder(name(thread));
    if (thread.daemon()) {
      text.append(" daemon");
    }
    text.append(" prio=").append(thread.priority()).append(' ').append(thread.state());
    if (thread.inNative()) {
      text.append(" (in native code)");
    }
    if (thread.suspended()) {
      text.append(" (suspended)");
    }
    if (ui) {
      text.append(" (UI thread)");
    }
    text.append('\n');
    List<StackTraceElement> frames = thread.frames();
    for (int depth = 0; depth < frames.size(); depth++) {
      text.append("\tat ").append(frames.get(depth)).append('\n');
      if (depth == 0 && thread.lock() != null) {
        text.append("\t- ").append(waiting(thread)).append(" <").append(thread.lock()).append('>');
        if (thread.lockOwner() >= 0) {
          text.append(" held by ").append(owner(thread));
        }
        text.append('\n');
      }
      for (Monitor monitor : thread.monitors()) {
        if (monitor.depth() == depth) {
          text.append("\t- locked <").append(monitor.lock()).append(">\n");
        }
      }
    }
    if (!thread.synchronizers().isEmpty()) {
      text.append("\tLocked synchronizers:\n");
      for (String synchronizer : thread.synchronizers()) {
        text.append("\t- <").append(synchronizer).append(">\n");
      }
    }
    return text.toString();
  }

  private static String waiting(ThreadStack thread) {
    if (thread.state() == Thread.State.BLOCKED) {
      return "waiting to lock";
    }
    StackTraceElement top = thread.frames().get(0);
    return "java.lang.Object".equals(top.getClassName()) && top.getMethodName().startsWith("wait") ? "waiting on" : "parking to wait for";
  }

  static String name(ThreadStack thread) {
    return '"' + thread.name() + "\" #" + thread.id();
  }

  private static String owner(ThreadStack thread) {
    return thread.lockOwnerName() == null ? "thread #" + thread.lockOwner() : '"' + thread.lockOwnerName() + "\" #" + thread.lockOwner();
  }

  /**
   * @return what the UI thread does, in a sentence: where it runs, or what it waits for and which thread holds it
   */
  public static String summary(ThreadDump dump, long uiThreadId) {
    ThreadStack ui = dump.thread(uiThreadId);
    if (ui == null) {
      return "The UI thread is not in the thread dump.";
    }
    StringBuilder text = new StringBuilder("The UI thread is ").append(ui.state());
    Set<Long> seen = new HashSet<>(Set.of(ui.id()));
    ThreadStack waiter = ui;
    for (int owners = 0; owners < MAX_OWNERS; owners++) {
      if (waiter.lock() == null || waiter.lockOwner() < 0) {
        text.append(where(waiter));
        if (waiter.lock() != null) {
          text.append(", waiting for <").append(waiter.lock()).append('>');
        }
        break;
      }
      text.append(waiter == ui ? ", it" : " and").append(" waits for <").append(waiter.lock()).append("> held by ").append(owner(waiter));
      ThreadStack owner = dump.thread(waiter.lockOwner());
      if (owner == null) {
        break;
      }
      if (!seen.add(owner.id())) {
        text.append(": a deadlock");
        break;
      }
      text.append(", which is ").append(owner.state());
      waiter = owner;
    }
    return text.append('.').toString();
  }

  private static String where(ThreadStack thread) {
    if (thread.frames().isEmpty()) {
      return "";
    }
    StackTraceElement top = thread.frames().get(0);
    StringBuilder text = new StringBuilder(" in ").append(top);
    if (thread.inNative()) {
      text.append(" (native code)");
    }
    if (isOfJava(top)) {
      // Thread.sleep or Object.wait tells little: the code of Eclipse that called it tells more.
      for (StackTraceElement frame : thread.frames()) {
        if (!isOfJava(frame)) {
          text.append(", called from ").append(frame);
          break;
        }
      }
    }
    return text.toString();
  }

  /**
   * @return {@code true} if the thread runs in the event loop of the workbench with only the widgets of SWT and their calls
   *         to the windowing system above it: it waits for the windowing system, or SWT handles a message of it. A nested
   *         event loop, e.g. of a dialog or of a plug-in, is not the one of the workbench.
   */
  public static boolean inEventLoop(ThreadStack thread) {
    if (thread.state() != Thread.State.RUNNABLE) {
      // a lock of Java: another thread makes it wait
      return false;
    }
    List<StackTraceElement> frames = thread.frames();
    for (int i = 0; i < frames.size(); i++) {
      StackTraceElement frame = frames.get(i);
      if ("org.eclipse.swt.widgets.Display".equals(frame.getClassName())
          && ("readAndDispatch".equals(frame.getMethodName()) || "sleep".equals(frame.getMethodName()))) {
        return i + 1 < frames.size() && frames.get(i + 1).getClassName().startsWith(WORKBENCH_LOOP);
      }
      if (!frame.getClassName().startsWith("org.eclipse.swt.widgets.") && !frame.getClassName().startsWith("org.eclipse.swt.internal.")) {
        return false;
      }
    }
    return false;
  }

  /** The class whose method runs the event loop of the workbench. */
  private static final String WORKBENCH_LOOP = "org.eclipse.e4.ui.internal.workbench.swt.PartRenderingEngine$";

  private static boolean isOfJava(StackTraceElement frame) {
    String type = frame.getClassName();
    return type.startsWith("java.") || type.startsWith("javax.") || type.startsWith("jdk.") || type.startsWith("sun.");
  }

  /**
   * @return the threads of a deadlock, for a report; empty if there is none
   */
  public static String deadlock(ThreadDump dump) {
    StringJoiner names = new StringJoiner(", ");
    for (long id : dump.deadlocked()) {
      ThreadStack thread = dump.thread(id);
      names.add(thread == null ? "thread #" + id : name(thread));
    }
    return names.toString();
  }
}
