package org.eclipse.efreeze.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.efreeze.model.Counters.Collector;
import org.junit.jupiter.api.Test;

class CountersTest {

  private static Counters counters(long cpu, long young, long youngMillis, long old, long oldMillis, long concurrent, long concurrentMillis) {
    Map<String, Collector> collectors = new LinkedHashMap<>();
    collectors.put("G1 Young Generation", new Collector(young, youngMillis));
    collectors.put("G1 Concurrent GC", new Collector(concurrent, concurrentMillis));
    collectors.put("G1 Old Generation", new Collector(old, oldMillis));
    return new Counters(cpu, collectors);
  }

  @Test
  void tellsWhatHappenedBetweenTwoReadings() {
    Counters before = counters(1000, 10, 200, 0, 0, 4, 900);
    Counters after = counters(1800, 13, 320, 1, 2500, 5, 1200);
    assertEquals(800, after.uiCpuSince(before));
    assertEquals(2620, after.gcPauseSince(before), "the concurrent collector does not stop the threads");
    assertEquals("2620 ms (G1 Young Generation: 3 collections, 120 ms; G1 Concurrent GC: 1 collection, 300 ms, beside the threads;"
        + " G1 Old Generation: 1 collection, 2500 ms)", after.gcSince(before));
    assertEquals("none", before.gcSince(before));
    assertEquals(0, before.gcPauseSince(before));
  }

  @Test
  void firstReadingCountsFromTheStartOfTheVirtualMachine() {
    Counters now = counters(50, 2, 30, 0, 0, 0, 0);
    assertEquals(30, now.gcPauseSince(null));
    assertEquals("30 ms (G1 Young Generation: 2 collections, 30 ms)", now.gcSince(null));
    assertEquals(-1, now.uiCpuSince(null));
  }

  @Test
  void processorTimeIsUnknownWhenTheVirtualMachineDoesNotTellIt() {
    assertEquals(-1, new Counters(-1, Map.of()).uiCpuSince(new Counters(10, Map.of())));
    assertEquals(-1, new Counters(10, Map.of()).uiCpuSince(new Counters(-1, Map.of())));
    assertEquals(0, new Counters(10, Map.of()).uiCpuSince(new Counters(12, Map.of())), "never less than nothing");
    assertEquals("none", new Counters(10, Map.of()).gcSince(null));
  }
}
