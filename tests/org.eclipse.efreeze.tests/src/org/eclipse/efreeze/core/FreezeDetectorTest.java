package org.eclipse.efreeze.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The detector with a clock and a UI thread of the test: a tick of the watchdog every 250 ms for a threshold of one second.
 */
class FreezeDetectorTest {

  private static final long MILLIS = 1_000_000;

  private long nanos = 5_000 * MILLIS;
  /** The tasks that the UI thread has not run yet. */
  private final List<Runnable> posted = new ArrayList<>();
  private boolean uiGone;
  /** What the detector has seen, in order. */
  private final List<String> events = new ArrayList<>();
  /** The time that the listener takes when a freeze starts, for example to write a thread dump. */
  private long lookTakes;

  private final FreezeDetector.Listener listener = new FreezeDetector.Listener() {
    @Override
    public void responsive() {
      events.add("responsive");
    }

    @Override
    public void started(long elapsedMillis) {
      events.add("started " + elapsedMillis);
      nanos += lookTakes * MILLIS;
    }

    @Override
    public void observed(long elapsedMillis, boolean allThreads) {
      events.add((allThreads ? "all threads " : "ui thread ") + elapsedMillis);
    }

    @Override
    public void ended(long durationMillis, long pausedMillis) {
      events.add("ended " + durationMillis + " paused " + pausedMillis);
    }

    @Override
    public void paused(long millis) {
      events.add("paused " + millis);
    }
  };

  private FreezeDetector detector(long threshold) {
    return new FreezeDetector(task -> {
      if (!uiGone) {
        posted.add(task);
      }
      return !uiGone;
    }, () -> nanos, threshold, listener);
  }

  /** The UI thread handles its events: it runs the tasks it was given. */
  private void answer() {
    posted.forEach(Runnable::run);
    posted.clear();
  }

  /** The watchdog sleeps, then looks at the UI thread. */
  private void tickAfter(FreezeDetector detector, long millis) {
    nanos += millis * MILLIS;
    assertTrue(detector.tick());
  }

  private List<String> eventsWithout(String ignored) {
    return events.stream().filter(event -> !event.equals(ignored)).toList();
  }

  @Test
  void uiThreadThatAnswersIsNotFrozen() {
    FreezeDetector detector = detector(1000);
    assertTrue(detector.tick());
    for (int i = 0; i < 40; i++) {
      // Even an answer that comes just before the threshold.
      for (int look = 0; look < 3; look++) {
        tickAfter(detector, 250);
      }
      nanos += 240 * MILLIS;
      answer();
      tickAfter(detector, 10);
    }
    assertEquals(List.of(), eventsWithout("responsive"));
    assertEquals(41, events.size(), "asked again after each answer");
    assertFalse(detector.isFrozen());
  }

  @Test
  void freezeStartsAtTheThresholdAndEndsWhenTheUiThreadAnswers() {
    FreezeDetector detector = detector(1000);
    assertEquals(250, detector.tickMillis());
    assertTrue(detector.tick());
    for (int i = 0; i < 3; i++) {
      tickAfter(detector, 250);
    }
    assertEquals(List.of("responsive"), events, "750 ms: not yet");
    tickAfter(detector, 250);
    assertEquals(List.of("responsive", "started 1000"), events);
    assertTrue(detector.isFrozen());
    assertEquals(1, posted.size(), "the UI thread is not asked again while it does not answer");

    // The UI thread is looked at again 500 ms later, then twice later each time; all the threads after 5 and 30 seconds.
    events.clear();
    for (int i = 0; i < 4 * 34; i++) {
      tickAfter(detector, 250);
    }
    assertEquals(List.of("ui thread 1500", "ui thread 2000", "ui thread 3000", "all threads 5000", "ui thread 9000", "ui thread 17000",
        "all threads 30000", "ui thread 33000"), events);

    events.clear();
    nanos += 100 * MILLIS;
    answer();
    tickAfter(detector, 150);
    assertEquals(List.of("ended 35100 paused 0", "responsive"), events);
    assertFalse(detector.isFrozen());
    assertEquals(1, posted.size(), "asked again");
  }

  @Test
  void tickIsAQuarterOfTheThresholdWithinLimits() {
    assertEquals(250, detector(1000).tickMillis());
    assertEquals(25, detector(100).tickMillis());
    assertEquals(20, detector(40).tickMillis());
    assertEquals(250, detector(60_000).tickMillis());
    assertEquals(20, detector(0).tickMillis());
  }

  @Test
  void pauseOfTheWholeVirtualMachineIsNotAFreeze() {
    FreezeDetector detector = detector(1000);
    assertTrue(detector.tick());
    answer();
    tickAfter(detector, 250);
    // The watchdog sleeps 250 ms and wakes up 5 seconds later: it was paused, as the UI thread.
    tickAfter(detector, 5250);
    assertEquals(List.of("responsive", "responsive", "paused 5000"), events);
    assertFalse(detector.isFrozen());
    answer();
    tickAfter(detector, 250);
    assertEquals(List.of("responsive", "responsive", "paused 5000", "responsive"), events);
    // A watchdog that is a little late is not a pause.
    tickAfter(detector, 250 + 499);
    assertEquals(4, events.size());
  }

  @Test
  void pauseDuringAFreezeIsToldAndDoesNotCount() {
    FreezeDetector detector = detector(1000);
    assertTrue(detector.tick());
    tickAfter(detector, 250);
    tickAfter(detector, 250);
    tickAfter(detector, 3250);
    assertEquals(List.of("responsive", "paused 3000"), events, "750 ms without an answer, the pause aside");
    tickAfter(detector, 250);
    assertEquals(List.of("responsive", "paused 3000", "started 4000"), events);
    answer();
    tickAfter(detector, 250);
    assertEquals(List.of("responsive", "paused 3000", "started 4000", "ended 4000 paused 3000", "responsive"), events);
  }

  @Test
  void whatALookTakesIsNotAPause() {
    FreezeDetector detector = detector(1000);
    lookTakes = 2000;
    assertTrue(detector.tick());
    for (int i = 0; i < 4; i++) {
      tickAfter(detector, 250);
    }
    // The thread dump took 2 seconds: the next look is 250 ms after it.
    tickAfter(detector, 250);
    assertEquals(List.of("responsive", "started 1000", "ui thread 3250"), events);
  }

  @Test
  void longThresholdSkipsTheLooksAtAllTheThreadsThatCameBefore() {
    FreezeDetector detector = detector(6000);
    assertTrue(detector.tick());
    for (int i = 0; i < 4 * 31; i++) {
      tickAfter(detector, 250);
    }
    assertEquals(List.of("responsive", "started 6000", "ui thread 6500", "ui thread 7000", "ui thread 8000", "ui thread 10000",
        "ui thread 14000", "ui thread 22000", "all threads 30000"), events);
  }

  @Test
  void watchEndsWhenTheUserInterfaceIsGone() {
    FreezeDetector detector = detector(1000);
    assertTrue(detector.tick());
    answer();
    uiGone = true;
    nanos += 250 * MILLIS;
    assertFalse(detector.tick());
  }
}
