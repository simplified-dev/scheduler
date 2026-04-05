# Scheduler

Dual-executor task scheduler with sync (ScheduledExecutorService) and async (virtual thread) execution.

## Package Structure
- `dev.simplified.scheduler` - 2 classes

## Key Classes
- `Scheduler` - Main entry point, implements `Executor`, manages sync + virtual thread executors, background cleaner every 30s
- `ScheduledTask` - Task handle wrapping `Future`, tracks running/repeating/cancelled/done state, consecutive error counter

## Dependencies
- `com.github.simplified-dev:collections:master-SNAPSHOT` (ConcurrentList, ConcurrentSet)
- `org.jetbrains:annotations` (@NotNull, @Range)
- `org.apache.logging.log4j:log4j-api` (Log4j2 logging)
- `org.projectlombok:lombok` (compile-only)
- JUnit 5 + Hamcrest (test)
- JMH (benchmarks)

## Build
```bash
./gradlew build
./gradlew test          # excludes @Tag("slow")
./gradlew slowTest      # shutdown/thread leak tests only
./gradlew jmh           # benchmarks
```

## Java Version
- Java 21 (toolchain enforced)

## Test Notes
- `@Tag("slow")` tests excluded from default `test` task
- `slowTest` task runs only slow-tagged tests (shutdown, thread leak detection)
- `SchedulerTest` uses `CountDownLatch`, `AtomicInteger` for async assertions

## Key Patterns
- Sync tasks: `ScheduledExecutorService.scheduleWithFixedDelay`
- Async tasks: self-scheduled via `TimeUnit.sleep` on virtual threads
- Cleanup: internal repeating task purges done tasks every 30s
- Shutdown hook registered in constructor
