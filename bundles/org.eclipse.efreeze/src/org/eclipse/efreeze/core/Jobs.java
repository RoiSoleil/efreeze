package org.eclipse.efreeze.core;

import java.util.*;
import java.util.concurrent.*;

import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;

/**
 * The jobs of Eclipse at the time of a freeze: the UI thread often waits for one of them.
 */
public class Jobs {

  private static final int MAX_RULE_LENGTH = 200;

  private Jobs() {
  }

  /**
   * Asks the job manager in another thread, and gives up after a while: in a frozen Eclipse the job manager may be what is
   * stuck, and the watchdog must go on.
   *
   * @return the jobs that run or wait to run, one by line, the running ones first
   */
  public static List<String> describe(long timeoutMillis) {
    FutureTask<List<String>> task = new FutureTask<>(Jobs::list);
    Thread thread = new Thread(task, "EFreeze jobs");
    thread.setDaemon(true);
    thread.start();
    try {
      return task.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      return List.of("(the job manager did not answer within " + timeoutMillis + " ms)");
    } catch (ExecutionException e) {
      return List.of("(not available: " + e.getCause() + ")");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return List.of("(not available: interrupted)");
    }
  }

  static List<String> list() {
    List<String> running = new ArrayList<>();
    List<String> waiting = new ArrayList<>();
    for (Job job : Job.getJobManager().find(null)) {
      int state = job.getState();
      if (state == Job.RUNNING) {
        running.add(describe(job, "RUNNING"));
      } else if (state == Job.WAITING) {
        waiting.add(describe(job, "WAITING"));
      }
    }
    running.addAll(waiting);
    return running;
  }

  static String describe(Job job, String state) {
    StringBuilder text = new StringBuilder(state).append(" \"").append(job.getName()).append('"');
    if (job.isSystem()) {
      text.append(" (system)");
    }
    Thread thread = job.getThread();
    if (thread != null) {
      text.append(", thread \"").append(thread.getName()).append('"');
    }
    ISchedulingRule rule = job.getRule();
    if (rule != null) {
      String described = String.valueOf(rule);
      text.append(", rule ").append(described.length() > MAX_RULE_LENGTH ? described.substring(0, MAX_RULE_LENGTH) + "..." : described);
    }
    return text.append(", ").append(job.getClass().getName()).toString();
  }
}
