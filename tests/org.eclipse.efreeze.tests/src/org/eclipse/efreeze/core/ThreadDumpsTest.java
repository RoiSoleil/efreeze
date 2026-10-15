package org.eclipse.efreeze.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.efreeze.model.ThreadDump;
import org.eclipse.efreeze.model.ThreadStack;
import org.eclipse.efreeze.model.ThreadStack.Monitor;
import org.junit.jupiter.api.Test;

class ThreadDumpsTest {

  static StackTraceElement frame(String type, String method, int line) {
    return new StackTraceElement(type, method, type.substring(type.lastIndexOf('.') + 1) + ".java", line);
  }

  /** A thread that runs. */
  static ThreadStack running(long id, String name, StackTraceElement... frames) {
    return new ThreadStack(id, name, Thread.State.RUNNABLE, false, 5, false, false, List.of(frames), null, -1, null, List.of(), List.of());
  }

  /** A thread that waits for a lock held by another thread. */
  static ThreadStack blocked(long id, String name, String lock, long owner, String ownerName, StackTraceElement... frames) {
    return new ThreadStack(id, name, Thread.State.BLOCKED, false, 5, false, false, List.of(frames), lock, owner, ownerName, List.of(),
        List.of());
  }

  @Test
  void threadIsWrittenWithItsWholeStackAndItsLocks() {
    ThreadStack thread = new ThreadStack(1, "main", Thread.State.BLOCKED, false, 6, false, false,
        List.of(frame("org.demo.Editor", "save", 10), frame("org.demo.Handler", "execute", 20), frame("org.eclipse.swt.widgets.Display", "readAndDispatch", 30)),
        "java.lang.Object@1f", 7, "Worker-1", List.of(new Monitor("org.demo.Model@2a", 1), new Monitor("org.demo.Other@2b", 1)),
        List.of("java.util.concurrent.locks.ReentrantLock$NonfairSync@3c"));
    assertEquals("""
        "main" #1 prio=6 BLOCKED (UI thread)
        \tat org.demo.Editor.save(Editor.java:10)
        \t- waiting to lock <java.lang.Object@1f> held by "Worker-1" #7
        \tat org.demo.Handler.execute(Handler.java:20)
        \t- locked <org.demo.Model@2a>
        \t- locked <org.demo.Other@2b>
        \tat org.eclipse.swt.widgets.Display.readAndDispatch(Display.java:30)
        \tLocked synchronizers:
        \t- <java.util.concurrent.locks.ReentrantLock$NonfairSync@3c>
        """, ThreadDumps.format(thread, true));

    ThreadStack worker = new ThreadStack(7, "Worker-1", Thread.State.WAITING, true, 5, true, true,
        List.of(frame("java.lang.Object", "wait0", -2), frame("org.demo.Queue", "take", 5)), "org.demo.Queue@4d", -1, null, List.of(), List.of());
    assertEquals("""
        "Worker-1" #7 daemon prio=5 WAITING (in native code) (suspended)
        \tat java.lang.Object.wait0(Native Method)
        \t- waiting on <org.demo.Queue@4d>
        \tat org.demo.Queue.take(Queue.java:5)
        """, ThreadDumps.format(worker, false));

    ThreadStack parked = new ThreadStack(8, "Worker-2", Thread.State.WAITING, false, 5, false, false,
        List.of(frame("jdk.internal.misc.Unsafe", "park", -2)), "java.util.concurrent.locks.ReentrantLock$NonfairSync@5e", 9, null, List.of(),
        List.of());
    assertEquals("""
        "Worker-2" #8 prio=5 WAITING
        \tat jdk.internal.misc.Unsafe.park(Native Method)
        \t- parking to wait for <java.util.concurrent.locks.ReentrantLock$NonfairSync@5e> held by thread #9
        """, ThreadDumps.format(parked, false));

    // The UI thread first, whatever its place in the dump.
    ThreadDump dump = new ThreadDump(List.of(worker, thread, parked), List.of());
    String text = ThreadDumps.format(dump, 1);
    assertTrue(text.startsWith("\"main\" #1 prio=6 BLOCKED (UI thread)\n"), text);
    assertTrue(text.indexOf("\n\n\"Worker-1\" #7") < text.indexOf("\n\n\"Worker-2\" #8"), text);
    assertTrue(ThreadDumps.format(dump, 99).startsWith("\n\"Worker-1\""), "a dump without the UI thread");
    assertEquals("\"empty\" #3 prio=5 RUNNABLE\n", ThreadDumps.format(running(3, "empty"), false));
  }

  @Test
  void summaryFollowsTheLocksToTheThreadThatMakesTheUiThreadWait() {
    ThreadStack ui = blocked(1, "main", "org.demo.Model@2a", 7, "Worker-1", frame("org.demo.Editor", "save", 10));
    ThreadStack worker = blocked(7, "Worker-1", "org.demo.Index@3b", 8, "Worker-2", frame("org.demo.Model", "update", 20));
    ThreadStack indexer = running(8, "Worker-2", frame("org.demo.Index", "build", 30));
    assertEquals("The UI thread is BLOCKED, it waits for <org.demo.Model@2a> held by \"Worker-1\" #7, which is BLOCKED and waits for"
        + " <org.demo.Index@3b> held by \"Worker-2\" #8, which is RUNNABLE in org.demo.Index.build(Index.java:30).",
        ThreadDumps.summary(new ThreadDump(List.of(ui, worker, indexer), List.of()), 1));
    // The UI thread alone: the other threads are only known by their names.
    assertEquals("The UI thread is BLOCKED, it waits for <org.demo.Model@2a> held by \"Worker-1\" #7.",
        ThreadDumps.summary(new ThreadDump(List.of(ui), List.of()), 1));

    ThreadStack back = blocked(7, "Worker-1", "org.demo.Display@4c", 1, "main", frame("org.demo.Model", "update", 20));
    ThreadDump deadlock = new ThreadDump(List.of(ui, back), List.of(1L, 7L, 12L));
    assertEquals("The UI thread is BLOCKED, it waits for <org.demo.Model@2a> held by \"Worker-1\" #7, which is BLOCKED and waits for"
        + " <org.demo.Display@4c> held by \"main\" #1: a deadlock.", ThreadDumps.summary(deadlock, 1));
    assertEquals("\"main\" #1, \"Worker-1\" #7, thread #12", ThreadDumps.deadlock(deadlock));
    assertEquals("", ThreadDumps.deadlock(new ThreadDump(List.of(ui), List.of())));
  }

  @Test
  void summaryTellsWhereTheUiThreadRunsOrWaits() {
    ThreadStack computing = running(1, "main", frame("org.demo.Parser", "parse", 10), frame("org.demo.Editor", "open", 20));
    assertEquals("The UI thread is RUNNABLE in org.demo.Parser.parse(Parser.java:10).",
        ThreadDumps.summary(new ThreadDump(List.of(computing), List.of()), 1));
    ThreadStack reading = new ThreadStack(1, "main", Thread.State.RUNNABLE, false, 5, true, false,
        List.of(frame("java.io.FileInputStream", "readBytes", -2)), null, -1, null, List.of(), List.of());
    assertEquals("The UI thread is RUNNABLE in java.io.FileInputStream.readBytes(Native Method) (native code).",
        ThreadDumps.summary(new ThreadDump(List.of(reading), List.of()), 1));
    ThreadStack joining = new ThreadStack(1, "main", Thread.State.WAITING, false, 5, false, false,
        List.of(frame("java.lang.Object", "wait", 5)), "org.demo.Job@5d", -1, null, List.of(), List.of());
    assertEquals("The UI thread is WAITING in java.lang.Object.wait(Object.java:5), waiting for <org.demo.Job@5d>.",
        ThreadDumps.summary(new ThreadDump(List.of(joining), List.of()), 1));
    // In a method of Java: the code of Eclipse that called it tells more.
    ThreadStack sleeping = new ThreadStack(1, "main", Thread.State.TIMED_WAITING, false, 5, false, false,
        List.of(frame("java.lang.Thread", "sleep0", -2), frame("java.lang.Thread", "sleep", 509), frame("org.demo.Editor", "save", 10),
            frame("org.demo.Handler", "execute", 20)),
        null, -1, null, List.of(), List.of());
    assertEquals("The UI thread is TIMED_WAITING in java.lang.Thread.sleep0(Native Method), called from org.demo.Editor.save(Editor.java:10).",
        ThreadDumps.summary(new ThreadDump(List.of(sleeping), List.of()), 1));
    ThreadStack waiter = blocked(1, "main", "org.demo.Model@2a", 7, "Worker-1", frame("org.demo.Editor", "save", 10));
    ThreadStack owner = new ThreadStack(7, "Worker-1", Thread.State.RUNNABLE, false, 5, true, false,
        List.of(frame("sun.nio.ch.SocketDispatcher", "read0", -2), frame("org.demo.Server", "ask", 40)), null, -1, null, List.of(), List.of());
    assertEquals("The UI thread is BLOCKED, it waits for <org.demo.Model@2a> held by \"Worker-1\" #7, which is RUNNABLE in"
        + " sun.nio.ch.SocketDispatcher.read0(Native Method) (native code), called from org.demo.Server.ask(Server.java:40).",
        ThreadDumps.summary(new ThreadDump(List.of(waiter, owner), List.of()), 1));
    assertEquals("The UI thread is RUNNABLE.", ThreadDumps.summary(new ThreadDump(List.of(running(1, "main")), List.of()), 1));
    assertEquals("The UI thread is not in the thread dump.", ThreadDumps.summary(new ThreadDump(List.of(computing), List.of()), 2));
  }

  @Test
  void realThreadDumpTellsWhichThreadHoldsTheLock() throws Exception {
    Object lock = new Object();
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder = new Thread(() -> {
      synchronized (lock) {
        held.countDown();
        try {
          release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          // The test is over.
        }
      }
    }, "lock holder");
    holder.start();
    assertTrue(held.await(10, TimeUnit.SECONDS));
    Thread blocked = new Thread(() -> {
      synchronized (lock) {
        lock.hashCode();
      }
    }, "blocked thread");
    blocked.start();
    try {
      // The thread of this test in the role of the UI thread.
      JvmProbe probe = new JvmProbe(blocked);
      assertEquals(blocked.threadId(), probe.uiThreadId());
      ThreadDump dump = null;
      for (int i = 0; i < 500 && (dump == null || dump.thread(blocked.threadId()).state() != Thread.State.BLOCKED); i++) {
        Thread.sleep(10);
        dump = probe.allThreads();
      }
      String summary = ThreadDumps.summary(dump, blocked.threadId());
      assertTrue(summary.matches("The UI thread is BLOCKED, it waits for <java\\.lang\\.Object@[0-9a-f]+> held by \"lock holder\" #"
          + holder.threadId() + ", which is TIMED_WAITING in .*\\."), summary);
      String text = ThreadDumps.format(dump, blocked.threadId());
      assertTrue(text.startsWith("\"blocked thread\" #" + blocked.threadId()), text);
      assertTrue(text.contains("\t- waiting to lock <java.lang.Object@"), text);
      assertTrue(text.contains("held by \"lock holder\" #" + holder.threadId() + "\n"), text);
      assertTrue(text.contains("\t- locked <java.lang.Object@"), text);
      assertTrue(text.contains("\tat org.eclipse.efreeze.core.ThreadDumpsTest.lambda$"), "the whole stacks, with the frames of this test: " + text);
      assertTrue(dump.threads().size() > 3, "all the threads");
      assertTrue(dump.deadlocked().isEmpty());

      ThreadDump alone = probe.uiThread();
      assertEquals(1, alone.threads().size());
      assertEquals("blocked thread", alone.threads().get(0).name());
      assertEquals("lock holder", alone.threads().get(0).lockOwnerName());
    } finally {
      release.countDown();
      holder.join(10_000);
      blocked.join(10_000);
    }
    assertTrue(new JvmProbe(blocked).uiThread().threads().isEmpty(), "a thread that has ended");
  }

  @Test
  void realDeadlockIsFound() throws Exception {
    ReentrantLock first = new ReentrantLock();
    ReentrantLock second = new ReentrantLock();
    CountDownLatch bothHold = new CountDownLatch(2);
    Thread one = new Thread(() -> lockBoth(first, second, bothHold), "deadlock one");
    Thread two = new Thread(() -> lockBoth(second, first, bothHold), "deadlock two");
    one.start();
    two.start();
    try {
      JvmProbe probe = new JvmProbe(one);
      ThreadDump dump = null;
      for (int i = 0; i < 500 && (dump == null || dump.deadlocked().size() < 2); i++) {
        Thread.sleep(10);
        dump = probe.allThreads();
      }
      assertEquals(List.of(one.threadId(), two.threadId()), dump.deadlocked().stream().sorted().toList());
      String summary = ThreadDumps.summary(dump, one.threadId());
      assertTrue(summary.startsWith("The UI thread is WAITING, it waits for <java.util.concurrent.locks.ReentrantLock$NonfairSync@"), summary);
      assertTrue(summary.endsWith("held by \"deadlock one\" #" + one.threadId() + ": a deadlock."), summary);
      assertTrue(ThreadDumps.deadlock(dump).contains("\"deadlock two\" #" + two.threadId()));
      assertTrue(ThreadDumps.format(dump, one.threadId()).contains("\tLocked synchronizers:\n\t- <java.util.concurrent.locks.ReentrantLock$NonfairSync@"));
    } finally {
      one.interrupt();
      two.interrupt();
      one.join(10_000);
      two.join(10_000);
    }
  }

  private static void lockBoth(ReentrantLock mine, ReentrantLock other, CountDownLatch bothHold) {
    mine.lock();
    try {
      bothHold.countDown();
      bothHold.await();
      other.lockInterruptibly();
      other.unlock();
    } catch (InterruptedException e) {
      // The test is over.
    } finally {
      mine.unlock();
    }
  }

  @Test
  void threadInTheEventLoopOfSwtOnly() {
    StackTraceElement loop = frame("org.eclipse.swt.widgets.Display", "readAndDispatch", 3744);
    StackTraceElement workbench = frame("org.eclipse.e4.ui.internal.workbench.swt.PartRenderingEngine$5", "run", 1160);

    assertTrue(ThreadDumps.inEventLoop(running(1, "main", frame("org.eclipse.swt.internal.win32.OS", "PeekMessage", -2), loop, workbench)));
    assertTrue(ThreadDumps.inEventLoop(running(1, "main", frame("org.eclipse.swt.internal.win32.OS", "GetWindowPlacement", -2),
        frame("org.eclipse.swt.widgets.Shell", "WM_MOVE", 2659), frame("org.eclipse.swt.internal.win32.OS", "PeekMessage", -2), loop, workbench)),
        "SWT handles a message of the windowing system");
    assertTrue(ThreadDumps.inEventLoop(running(1, "main", frame("org.eclipse.swt.internal.win32.OS", "WaitMessage", -2),
        frame("org.eclipse.swt.widgets.Display", "sleep", 4600), workbench)));
    assertFalse(ThreadDumps.inEventLoop(running(1, "main", frame("org.demo.Editor", "save", 10), frame("org.eclipse.swt.widgets.Display",
        "runAsyncMessages", 4136), loop, workbench)), "code of a plug-in");
    assertFalse(ThreadDumps.inEventLoop(running(1, "main", frame("org.eclipse.swt.internal.win32.OS", "PeekMessage", -2))), "no event loop");
    assertFalse(ThreadDumps.inEventLoop(running(1, "main", frame("org.eclipse.swt.internal.win32.OS", "WaitMessage", -2),
        frame("org.eclipse.swt.widgets.Display", "sleep", 4600), frame("org.demo.Waiter", "waitFor", 12), workbench)),
        "an event loop of a plug-in, which may wait for ever");
    assertFalse(ThreadDumps.inEventLoop(running(1, "main", frame("org.eclipse.swt.internal.win32.OS", "PeekMessage", -2), loop,
        frame("org.eclipse.jface.window.Window", "runEventLoop", 777), workbench)), "the event loop of a dialog");
    assertFalse(ThreadDumps.inEventLoop(running(1, "main", frame("org.eclipse.swt.graphics.TextLayout", "draw", 900),
        frame("org.eclipse.swt.custom.StyledText", "handlePaint", 6000), frame("org.eclipse.swt.widgets.Composite", "WM_PAINT", 1537), loop,
        workbench)), "SWT computes what Eclipse gave it");
    assertFalse(ThreadDumps.inEventLoop(blocked(1, "main", "java.lang.Object@1a", 7, "Worker-1", frame("org.eclipse.swt.widgets.Synchronizer",
        "removeFirst", 90), frame("org.eclipse.swt.widgets.Display", "runAsyncMessages", 4136), loop, workbench)),
        "another thread holds a lock of Java");
    assertFalse(ThreadDumps.inEventLoop(running(1, "main")));
  }
}
