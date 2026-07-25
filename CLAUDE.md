# Scheduler

Dual-executor task scheduler with sync (ScheduledExecutorService) and async (virtual thread) execution.

## Build
```bash
./gradlew test          # excludes @Tag("slow")
./gradlew slowTest      # shutdown/thread leak tests only
./gradlew jmh           # benchmarks
```

## Key Patterns
- Sync tasks run on `ScheduledExecutorService.scheduleWithFixedDelay`; async tasks self-schedule via `TimeUnit.sleep` on virtual threads.
- An internal repeating task purges done tasks every 30s.
- A shutdown hook is registered in the `Scheduler` constructor.
