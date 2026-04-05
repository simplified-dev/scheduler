package dev.simplified.scheduler;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class SchedulerBenchmark {

    private Scheduler scheduler;

    @Setup(Level.Iteration)
    public void setup() {
        scheduler = new Scheduler();
    }

    @TearDown(Level.Iteration)
    public void teardown() {
        scheduler.shutdown();
    }

    @Benchmark
    public ScheduledTask schedule_oneShot() {
        return scheduler.schedule(() -> {}, 1, 0, TimeUnit.HOURS);
    }

    @Benchmark
    public void schedule_and_cancel() {
        ScheduledTask task = scheduler.schedule(() -> {}, 1, 0, TimeUnit.HOURS);
        task.cancel();
    }

    @Benchmark
    public ScheduledTask schedule_repeating() {
        return scheduler.schedule(() -> {}, 1, 1, TimeUnit.HOURS);
    }

    @Benchmark
    public int getTasks_snapshot() {
        return scheduler.getTasks().size();
    }
}
