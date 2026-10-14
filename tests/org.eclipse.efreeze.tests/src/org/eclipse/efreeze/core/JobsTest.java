package org.eclipse.efreeze.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.jupiter.api.Test;

class JobsTest {

  /** A rule that only one job has at a time. */
  private static final ISchedulingRule RULE = new ISchedulingRule() {
    @Override
    public boolean contains(ISchedulingRule rule) {
      return rule == this;
    }

    @Override
    public boolean isConflicting(ISchedulingRule rule) {
      return rule == this;
    }

    @Override
    public String toString() {
      return "rule of the test " + "x".repeat(300);
    }
  };

  @Test
  void runningAndWaitingJobsAreListed() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Job running = Job.create("EFreeze running job", monitor -> {
      started.countDown();
      try {
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        // The test is over.
      }
    });
    running.setRule(RULE);
    Job waiting = new Job("EFreeze waiting job") {
      @Override
      protected IStatus run(IProgressMonitor monitor) {
        return Status.OK_STATUS;
      }
    };
    waiting.setRule(RULE);
    waiting.setSystem(true);
    Job sleeping = Job.create("EFreeze sleeping job", monitor -> {
    });
    try {
      running.schedule();
      assertTrue(started.await(10, TimeUnit.SECONDS));
      waiting.schedule();
      sleeping.schedule(60_000);
      List<String> jobs = Jobs.describe(5000);
      // Its name is also in the name of its thread, which another job may have just left.
      String ran = jobs.stream().filter(job -> job.startsWith("RUNNING \"EFreeze running job\"")).findFirst().orElseThrow();
      assertTrue(ran.startsWith("RUNNING \"EFreeze running job\", thread \"Worker-"), ran);
      assertTrue(ran.contains(", rule rule of the test " + "x".repeat(183) + "..., "), "a long rule is cut: " + ran);
      assertTrue(ran.endsWith(", " + running.getClass().getName()), ran);
      String waited = jobs.stream().filter(job -> job.contains("\"EFreeze waiting job\"")).findFirst().orElseThrow();
      assertTrue(waited.startsWith("WAITING \"EFreeze waiting job\" (system), rule rule of the test"), waited);
      assertFalse(waited.contains("thread"), waited);
      assertTrue(jobs.indexOf(ran) < jobs.indexOf(waited), "the running jobs first");
      assertTrue(jobs.stream().noneMatch(job -> job.contains("\"EFreeze sleeping job\"")), "a job that sleeps makes nobody wait");
    } finally {
      release.countDown();
      sleeping.cancel();
      running.join(10_000, null);
      waiting.join(10_000, null);
    }
  }

  @Test
  void jobManagerThatDoesNotAnswerIsNotWaitedFor() {
    // No time at all to answer: as a job manager that is stuck with the rest of Eclipse.
    assertEquals(List.of("(the job manager did not answer within 0 ms)"), Jobs.describe(0));
    Thread.currentThread().interrupt();
    try {
      assertEquals(List.of("(not available: interrupted)"), Jobs.describe(5000));
    } finally {
      assertTrue(Thread.interrupted());
    }
  }
}
