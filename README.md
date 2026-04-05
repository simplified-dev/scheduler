# Scheduler

A dual-executor task scheduler for Java 21 that supports both synchronous (`ScheduledExecutorService`) and asynchronous (virtual thread) execution. Provides one-shot and repeating task scheduling with cancellation support and automatic background cleanup every 30 seconds. Implements the `Executor` interface.

> [!IMPORTANT]
> This library requires **Java 21+** for virtual thread support (`Executors.newVirtualThreadPerTaskExecutor()`).

## Table of Contents

- [Features](#features)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Installation](#installation)
  - [Usage](#usage)
- [Architecture](#architecture)
  - [Dual-Executor Model](#dual-executor-model)
  - [Task Lifecycle](#task-lifecycle)
- [API Overview](#api-overview)
- [Testing](#testing)
- [Project Structure](#project-structure)
- [Contributing](#contributing)
- [License](#license)

## Features

- **Dual execution model** - Synchronous tasks run on a single-threaded `ScheduledExecutorService`; asynchronous tasks run on virtual threads
- **One-shot and repeating** - Schedule tasks for immediate or delayed execution, with optional fixed-delay repetition
- **Virtual thread integration** - Async tasks use `Executors.newVirtualThreadPerTaskExecutor()`, yielding carrier threads during waits via `TimeUnit.sleep`
- **Cancellation support** - Cancel tasks by reference or by ID, with optional thread interruption
- **Automatic cleanup** - Background cleaner purges completed tasks from the internal list every 30 seconds
- **Executor contract** - Implements `java.util.concurrent.Executor`, so the scheduler can be passed anywhere an `Executor` is expected
- **Error tracking** - Consecutive execution errors are counted per task and reset on success
- **Thread leak detection** - Static `leakedThreads()` utility for diagnosing non-daemon threads after shutdown

## Getting Started

### Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| [Java](https://adoptium.net/) | **21+** | Required for virtual threads |
| [Gradle](https://gradle.org/) | **9.4+** | Build tool (wrapper included) |
| [Git](https://git-scm.com/) | 2.x+ | For cloning the repository |

### Installation

<details>
<summary>Gradle (Kotlin DSL)</summary>

Add the JitPack repository and dependency to your `build.gradle.kts`:

```kotlin
repositories {
    maven(url = "https://jitpack.io")
}

dependencies {
    implementation("com.github.simplified-dev:scheduler:master-SNAPSHOT")
}
```

</details>

<details>
<summary>Gradle (Groovy DSL)</summary>

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.simplified-dev:scheduler:master-SNAPSHOT'
}
```

</details>

<details>
<summary>Maven</summary>

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.simplified-dev</groupId>
    <artifactId>scheduler</artifactId>
    <version>master-SNAPSHOT</version>
</dependency>
```

</details>

### Usage

#### Basic Scheduling

```java
Scheduler scheduler = new Scheduler();

// One-shot synchronous task (immediate)
scheduler.schedule(() -> System.out.println("Hello, sync!"));

// One-shot async task with 500ms delay
scheduler.scheduleAsync(() -> System.out.println("Hello, async!"), 500);

// Repeating sync task: 100ms initial delay, 50ms between executions
ScheduledTask task = scheduler.schedule(() -> doWork(), 100, 50);

// Repeating async task with default 50ms period
ScheduledTask repeating = scheduler.repeatAsync(() -> pollSomething());
```

#### Cancellation

```java
// Cancel by reference
task.cancel();

// Cancel by ID, optionally interrupting
scheduler.cancel(task.getId(), true);
```

#### Using as an Executor

```java
// Pass anywhere an Executor is expected (delegates to virtual thread executor)
Executor executor = new Scheduler();
executor.execute(() -> handleRequest());
```

#### Shutdown

```java
scheduler.shutdown();
// Check state
scheduler.isShutdown();    // both executors shut down?
scheduler.isTerminated();  // both executors terminated?
```

> [!NOTE]
> A JVM shutdown hook is registered automatically to call `shutdown()` on the scheduler.

## Architecture

### Dual-Executor Model

```
Scheduler
├── syncExecutor   (ScheduledExecutorService, single daemon thread "scheduler-sync")
│   ├── Synchronous one-shot tasks (schedule/scheduleWithFixedDelay)
│   ├── Synchronous repeating tasks
│   └── Background cleaner (every 30s)
└── virtualExecutor (newVirtualThreadPerTaskExecutor)
    ├── Asynchronous one-shot tasks (self-scheduled via TimeUnit.sleep)
    └── Asynchronous repeating tasks (self-scheduled loop)
```

- **Synchronous tasks** are scheduled directly on the `ScheduledExecutorService`. Only one runs at a time, making this executor safe for lightweight, ordering-sensitive work.
- **Asynchronous tasks** are submitted immediately to the virtual thread executor. They manage their own delay and repetition internally via `TimeUnit.sleep`, yielding the carrier thread during waits so no platform resources are consumed while idle.

### Task Lifecycle

Each `ScheduledTask` is assigned a monotonically increasing ID at creation time and tracks its state:

| State | Description |
|-------|-------------|
| `running` | The task's `Runnable` is actively executing |
| `repeating` | The task was scheduled with a positive repeat delay |
| `cancelled` | `cancel()` has been called |
| `done` | Completed normally, cancelled, or failed (one-shot) |

> [!TIP]
> Use `getConsecutiveErrors()` to monitor task health. The counter resets to zero after each successful execution and increments on each caught exception.

## API Overview

| Class | Description |
|-------|-------------|
| `Scheduler` | Main scheduler implementing `Executor` - creates and manages tasks on both executors |
| `ScheduledTask` | Task handle wrapping a `Future` with lifecycle state, cancellation, and error tracking |

### Scheduler Methods

| Method | Description |
|--------|-------------|
| `schedule(Runnable)` | One-shot sync task, immediate |
| `schedule(Runnable, delay)` | One-shot sync task, delayed (ms) |
| `schedule(Runnable, initial, repeat)` | Repeating sync task (ms) |
| `schedule(Runnable, initial, repeat, unit)` | Repeating sync task (custom unit) |
| `scheduleAsync(...)` | Async variants of all the above |
| `repeat(Runnable)` | Convenience: sync, no delay, 50ms period |
| `repeatAsync(Runnable)` | Convenience: async, no delay, 50ms period |
| `cancel(id)` / `cancel(task)` | Cancel a task by ID or reference |
| `getTasks()` | Unmodifiable snapshot of tracked tasks |
| `shutdown()` | Orderly shutdown of both executors |
| `execute(Runnable)` | `Executor` contract - delegates to virtual thread executor |
| `sleep(millis)` | Static utility - `Thread.sleep` with swallowed `InterruptedException` |
| `leakedThreads()` | Static utility - non-daemon threads still alive (filters JVM/Gradle internals) |

## Testing

```bash
# Run standard tests (excludes slow tests)
./gradlew test

# Run slow integration tests (shutdown, thread leak detection)
./gradlew slowTest

# Run all tests
./gradlew test slowTest

# Run JMH benchmarks
./gradlew jmh

# Full build
./gradlew build
```

> [!NOTE]
> Tests tagged with `@Tag("slow")` are excluded from the default `test` task and run separately via the `slowTest` task. These tests verify shutdown behavior and thread leak detection.

## Project Structure

```
scheduler/
├── src/
│   ├── main/java/dev/simplified/scheduler/
│   │   ├── Scheduler.java          # Main scheduler (Executor impl, dual executors, cleanup)
│   │   └── ScheduledTask.java      # Task handle (Future wrapper, lifecycle, error tracking)
│   ├── test/java/dev/simplified/scheduler/
│   │   └── SchedulerTest.java      # Unit + integration tests (JUnit 5, @Tag("slow"))
│   └── jmh/java/dev/simplified/scheduler/
│       └── ...                     # JMH benchmark sources
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   ├── libs.versions.toml          # Version catalog
│   └── wrapper/
└── LICENSE.md
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, code style guidelines, and how to submit a pull request.

## License

This project is licensed under the **Apache License 2.0** - see [LICENSE.md](LICENSE.md) for the full text.
