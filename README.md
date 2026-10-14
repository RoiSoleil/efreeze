# EFreeze : thread dumps of the UI freezes of Eclipse

[![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/RoiSoleil/efreeze/build.yml)](https://github.com/RoiSoleil/efreeze/actions/workflows/build.yml)
[![codecov](https://codecov.io/gh/RoiSoleil/efreeze/branch/main/graph/badge.svg)](https://codecov.io/gh/RoiSoleil/efreeze)
[![GitHub](https://img.shields.io/github/license/RoiSoleil/efreeze)](LICENSE)

EFreeze watches the user interface of Eclipse. When it does not answer for more than a second, the stacks of all the
threads are saved in a file, while Eclipse is still frozen: the file is there even if Eclipse never answers again and
has to be killed. These files are what is needed to find, later, what froze Eclipse and to fix it, for example
with [Claude Code](https://claude.com/claude-code).

There is nothing to set up: EFreeze starts with Eclipse, in every workspace.

# Features

- **A report by freeze**, in the folder of the plug-in in the metadata of the workspace:
  `<workspace>/.metadata/.plugins/org.eclipse.efreeze/freeze-2026-10-14_10-32-05.123-2350ms.txt`. The name tells when
  the freeze started and how long it lasted; a report without a duration is the one of an Eclipse that was closed or
  killed while it was frozen.
- **All the threads as soon as the freeze is detected**, with their whole stacks, the locks they hold and the one
  they wait for, the deadlocks, and the jobs that run or wait to run. Again after 5 and 30 seconds if the freeze goes on.
- **A summary in one line**: what the UI thread does, and when it waits for a lock, the thread that holds it and what
  that thread does.
  `Summary: The UI thread is BLOCKED, it waits for <java.lang.Object@1b2c3d> held by "Worker-4: Building" #88, which is RUNNABLE in ...`
- **The UI thread again while the freeze lasts** (after 0.5 s, 1 s, 2 s, 4 s...): the same stack means that it is
  stuck, another one that it works.
- **What the freeze was made of**: the processor time used by the UI thread (it computes, or it waits), the time
  spent in garbage collection, the memory.
- **The versions** of Eclipse, of Java, of the system and of every plug-in, in one `session-*.txt` file by session of
  Eclipse: they lead to the sources of the frames of the stacks.
- A line in the *Error Log* once Eclipse answers again, with the path of the report.
- **Sharing the reports on GitHub**, off by default: in *Window > Preferences > EFreeze*, each report of a freeze is
  then sent, once Eclipse answers again, to a [relay](relay/README.md) that opens a public issue for it in this
  repository; no account on GitHub is needed. Anyone can read these issues: the stacks show the names of the classes, of
  the projects and the paths of the files (the home folder is replaced by `~`). The relay opens 20 issues a day at most.
  The report goes through the HTTP transport of Eclipse (ECF) and its proxies (*General > Network Connections*); the
  HTTP client of Java does not authenticate with Basic to a proxy over HTTPS, unless Eclipse is started with
  `-Djdk.http.auth.tunneling.disabledSchemes=`.
- The 100 newest reports are kept. The threshold, the folder and the number of reports are in
  *Window > Preferences > EFreeze*; the detection can be turned off there.

A pause of the whole Java virtual machine (the watchdog of EFreeze is paused too) is not a freeze of the UI thread:
it is reported in a `gc-pause-*.txt` file when the garbage collector explains it, and ignored otherwise (a computer
that was suspended). A UI thread suspended by a debugger and an Eclipse that closes are not reported either.

# Analyzing the freezes

Give the folder of the reports to Claude Code, with the sources of the plug-ins that appear in the stacks if you have
them:

```
The files of ~/workspace/.metadata/.plugins/org.eclipse.efreeze are thread dumps of UI freezes of my Eclipse.
Group them by cause, the longest first, and tell me for each cause what blocks the UI thread and how to fix it.
```

`grep -h "^Summary" freeze-*.txt | sort | uniq -c | sort -rn` gives a first look at what freezes Eclipse most often.

# EFreeze and the UI Responsiveness Monitoring of Eclipse

Eclipse has a detector of its own (*Window > Preferences > General > UI Responsiveness Monitoring*), which some
packages of eclipse.org turn on by default (the one for RCP developers does): it writes the stacks in the *Error Log*
once a freeze is over. EFreeze
does not use it and does not change its preferences; both can run together. What EFreeze adds: one file by freeze in
a folder of its own, written during the freeze, with the locks, the thread that holds the one the UI thread waits
for, the jobs, the processor and garbage collection times, and the versions of the plug-ins.

# How it works

A thread of its own, the watchdog, asks the UI thread again and again to run an empty task between two of its events
(4 times a second with the default threshold). A task that waits longer than the threshold is a freeze. A dialog
has an event loop of its own that runs the task: an open dialog is no freeze. During a freeze the watchdog only
reads the Java virtual machine and writes its file: it never waits for the UI thread.

Limits: EFreeze starts when the workbench has started (`org.eclipse.ui.startup`), a freeze during the startup itself
is not seen. Some loops of the operating system that do not run the tasks of Eclipse could be taken for a freeze; the
stack of the UI thread in the report shows them. EFreeze is tested on Linux.

# Update Site

You can find the latest build of EFreeze here:

https://github.com/RoiSoleil/efreeze/raw/update-site/latest/

# Build

Requires JDK 21 and Maven 3.9 (the Apache distribution: the Maven packaged by some Linux distributions does not work
with Tycho).

```bash
mvn clean install   # update site in update-site/org.eclipse.efreeze/target/repository
```

The tests freeze the UI thread of a real Eclipse workbench: a window opens on the current display (use `xvfb-run` on a
server, `GDK_BACKEND=x11` on Wayland), `-DskipTests` skips them.

JaCoCo measures the coverage of the plug-in by the tests; `tests/org.eclipse.efreeze.coverage` writes the report in
`target/site/jacoco-aggregate` and GitHub Actions sends it to [Codecov](https://codecov.io/gh/RoiSoleil/efreeze).

# License

[Eclipse Public License, v2.0](http://www.eclipse.org/legal/epl-v20.html)
