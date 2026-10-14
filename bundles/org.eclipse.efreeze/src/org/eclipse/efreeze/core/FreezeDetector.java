package org.eclipse.efreeze.core;

/**
 * Watches the UI thread: again and again it is asked to run an empty task between two of its events, and a freeze is a task
 * that waits longer than the threshold. A dialog, with an event loop of its own, runs the task: it is no freeze.
 * <p>
 * {@link #tick()} is one look at the UI thread, called by the thread of the watchdog every {@link #tickMillis()}.
 */
public class FreezeDetector {

  /** The UI thread. */
  public interface Ui {
    /**
     * Asks the UI thread to run a task as soon as it handles its events.
     *
     * @return {@code false} if the user interface is gone: there is nothing more to watch
     */
    boolean post(Runnable task);
  }

  /** The time, which a test makes go as it wants. */
  public interface Clock {
    long nanos();
  }

  /** What the detector sees. Called by the thread of the watchdog, never by the UI thread. */
  public interface Listener {

    /** The UI thread has answered and is asked again: the time a freeze would start. */
    void responsive();

    /**
     * The UI thread has not answered for the threshold: a freeze has started.
     *
     * @param elapsedMillis
     *          the time since it was asked
     */
    void started(long elapsedMillis);

    /**
     * The freeze goes on, and it is time to look at it again.
     *
     * @param allThreads
     *          {@code true} to look at all the threads, {@code false} at the UI thread only
     */
    void observed(long elapsedMillis, boolean allThreads);

    /**
     * The UI thread answers again.
     *
     * @param pausedMillis
     *          the part of the freeze during which the whole virtual machine was paused
     */
    void ended(long durationMillis, long pausedMillis);

    /**
     * The whole virtual machine was paused, the watchdog included: a garbage collection, a computer that was suspended, a
     * debugger. This is not a freeze of the UI thread, and does not count as time without an answer.
     */
    void paused(long millis);
  }

  /** When all the threads are looked at again, since the start of a freeze. */
  static final long[] ALL_THREADS_AT = { 5_000, 30_000 };
  /** When the UI thread is looked at again after a freeze is detected; twice later each time. */
  static final long FIRST_SAMPLE = 500;
  private static final long NANOS = 1_000_000;

  private final Ui ui;
  private final Clock clock;
  private final Listener listener;
  private final long threshold;
  private final long tick;

  private Ping ping;
  private long lastTick = -1;
  /** The time the virtual machine was paused since the UI thread was asked. */
  private long paused;
  private volatile boolean frozen;
  /** When the freeze was detected, since the UI thread was asked. */
  private long detected;
  private long nextSample;
  private int nextAllThreads;

  /** The task given to the UI thread: it tells when it ran. */
  private final class Ping implements Runnable {
    final long posted = clock.nanos();
    volatile long answered = -1;

    @Override
    public void run() {
      answered = clock.nanos();
    }
  }

  /**
   * @param thresholdMillis
   *          the time without an answer of the UI thread that makes a freeze
   */
  public FreezeDetector(Ui ui, Clock clock, long thresholdMillis, Listener listener) {
    this.ui = ui;
    this.clock = clock;
    this.listener = listener;
    this.threshold = Math.max(1, thresholdMillis);
    this.tick = Math.max(20, Math.min(250, threshold / 4));
  }

  /**
   * @return the time between two looks at the UI thread: short enough to see a freeze soon after the threshold, long enough
   *         to let an idle Eclipse sleep
   */
  public long tickMillis() {
    return tick;
  }

  /**
   * @return {@code true} while a freeze is going on
   */
  public boolean isFrozen() {
    return frozen;
  }

  /**
   * Looks at the UI thread once.
   *
   * @return {@code false} when the user interface is gone
   */
  public boolean tick() {
    boolean watching = look(clock.nanos());
    // From here to the next look: what a look takes, a thread dump for example, is not a pause.
    lastTick = clock.nanos();
    return watching;
  }

  private boolean look(long now) {
    // A watchdog that wakes up much later than asked did not run either: the whole virtual machine was paused.
    long late = lastTick < 0 ? 0 : (now - lastTick) / NANOS - tick;
    if (late >= Math.max(threshold / 2, 2 * tick)) {
      paused += late;
      listener.paused(late);
    }
    if (ping != null && ping.answered < 0) {
      long elapsed = (now - ping.posted) / NANOS;
      if (!frozen) {
        if (elapsed - paused >= threshold) {
          frozen = true;
          detected = elapsed;
          nextSample = FIRST_SAMPLE;
          nextAllThreads = 0;
          while (nextAllThreads < ALL_THREADS_AT.length && ALL_THREADS_AT[nextAllThreads] <= elapsed) {
            nextAllThreads++;
          }
          listener.started(elapsed);
        }
      } else if (nextAllThreads < ALL_THREADS_AT.length && elapsed >= ALL_THREADS_AT[nextAllThreads]) {
        nextAllThreads++;
        skipSamples(elapsed);
        listener.observed(elapsed, true);
      } else if (elapsed >= detected + nextSample) {
        skipSamples(elapsed);
        listener.observed(elapsed, false);
      }
      return true;
    }
    if (frozen) {
      frozen = false;
      listener.ended((ping.answered - ping.posted) / NANOS, paused);
    }
    paused = 0;
    listener.responsive();
    ping = new Ping();
    return ui.post(ping);
  }

  /** The next look at the UI thread alone is after this one, twice later each time. */
  private void skipSamples(long elapsed) {
    while (detected + nextSample <= elapsed) {
      nextSample *= 2;
    }
  }
}
