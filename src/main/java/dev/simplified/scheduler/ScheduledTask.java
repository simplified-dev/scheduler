package dev.simplified.scheduler;

import lombok.AccessLevel;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A handle for a task submitted to a {@link Scheduler}, wrapping a {@link Future}
 * and exposing lifecycle state (running, repeating, done, cancelled).
 * <p>
 * Each task is assigned a monotonically increasing {@linkplain #getId() id} at creation time.
 * A task may be <em>one-shot</em> (executes once after an initial delay) or <em>repeating</em>
 * (re-executes with a fixed delay between the end of one execution and the start of the next).
 * <p>
 * Platform-thread tasks use {@link ScheduledExecutorService#scheduleWithFixedDelay} for
 * repeating execution. Virtual-thread tasks are <em>self-scheduled</em> - they manage delay
 * and repetition internally via {@link TimeUnit#sleep}, yielding the carrier thread during
 * waits so that no platform resources are consumed while idle.
 * <p>
 * Execution errors are caught, logged, and tracked via {@link #getConsecutiveErrors()}; the
 * counter resets to zero after every successful execution.
 *
 * @see Scheduler
 */
@Getter
public final class ScheduledTask implements Runnable {

    /** Global counter used to assign a unique id to every {@code ScheduledTask}. */
    private static final AtomicLong currentId = new AtomicLong(1);

    /** Epoch millisecond timestamp recorded when this task was created. */
    private final long addedTime = System.currentTimeMillis();

    /** Unique identifier for this task, assigned from {@link #currentId}. */
    private final long id;

    /** The delay before the first execution, expressed in {@link #timeUnit}. */
    private final long initialDelay;

    /**
     * The delay between the end of one execution and the start of the next,
     * expressed in {@link #timeUnit}. A value of {@code 0} indicates a one-shot task.
     */
    private final long period;

    /** The time unit for both {@link #initialDelay} and {@link #period}. */
    private final @NotNull TimeUnit timeUnit;

    /** {@code true} while the task's {@link Runnable} is actively executing. */
    private volatile boolean running;

    /** {@code true} if this task was scheduled with a positive {@link #period}. */
    private volatile boolean repeating;

    /** {@code true} after {@link #cancel()} has been called on this task. */
    @Getter(AccessLevel.NONE)
    private volatile boolean cancelled;

    /**
     * Rolling count of consecutive execution failures. Reset to zero after each
     * successful execution; incremented on each caught exception.
     */
    private AtomicInteger consecutiveErrors = new AtomicInteger(0);

    /** {@code true} when this task manages its own delay and repeat via {@link TimeUnit#sleep}. */
    @Getter(AccessLevel.NONE)
    private final boolean async;

    @Getter(AccessLevel.NONE)
    private final @NotNull Runnable runnableTask;

    @Getter(AccessLevel.NONE)
    private final @NotNull Future<?> future;

    /**
     * Creates and immediately schedules a new task on the given executor.
     * <p>
     * Synchronous tasks ({@code async = false}) require a {@link ScheduledExecutorService}
     * and delegate delay and repetition to the executor via
     * {@link ScheduledExecutorService#schedule} or
     * {@link ScheduledExecutorService#scheduleWithFixedDelay}.
     * <p>
     * Asynchronous tasks ({@code async = true}) are submitted immediately via
     * {@link ExecutorService#submit} and manage their own delay and repetition internally
     * via {@link TimeUnit#sleep}, making them suitable for virtual thread executors where
     * sleeping yields the carrier thread rather than blocking a platform thread.
     *
     * @param executorService the executor that will run this task
     * @param task the work to execute
     * @param initialDelay the delay before the first execution
     * @param repeatDelay the delay between end-of-execution and the next start ({@code 0} for one-shot)
     * @param timeUnit the time unit for {@code initialDelay} and {@code repeatDelay}
     * @param async {@code true} for self-scheduled virtual-thread execution;
     *     {@code false} for platform-thread scheduling
     */
    ScheduledTask(
        @NotNull ExecutorService executorService,
        @NotNull final Runnable task,
        @Range(from = 0, to = Long.MAX_VALUE) long initialDelay,
        @Range(from = 0, to = Long.MAX_VALUE) long repeatDelay,
        @NotNull TimeUnit timeUnit,
        boolean async
    ) {
        this.id = currentId.getAndIncrement();
        this.runnableTask = task;
        this.initialDelay = initialDelay;
        this.period = repeatDelay;
        this.timeUnit = timeUnit;
        this.repeating = this.period > 0;
        this.async = async;

        // Schedule Task
        if (async)
            this.future = executorService.submit(this);
        else {
            ScheduledExecutorService scheduledExecutor = (ScheduledExecutorService) executorService;

            if (this.isRepeating())
                this.future = scheduledExecutor.scheduleWithFixedDelay(this, initialDelay, repeatDelay, timeUnit);
            else
                this.future = scheduledExecutor.schedule(this, initialDelay, timeUnit);
        }
    }

    /**
     * Attempts to cancel this task without interrupting a running execution.
     * <p>
     * Equivalent to {@code cancel(false)}.
     *
     * @return {@code true} if the task was successfully cancelled
     * @see #cancel(boolean)
     */
    public boolean cancel() {
        return this.cancel(false);
    }

    /**
     * Attempts to cancel this task, optionally interrupting a running execution.
     * <p>
     * For synchronous (platform-thread) tasks, cancellation delegates directly to the
     * underlying {@link Future#cancel(boolean)}. For asynchronous (virtual-thread) tasks,
     * a volatile flag is also set to signal the internal sleep loop to exit.
     *
     * @param mayInterruptIfRunning {@code true} to interrupt the executing thread;
     *                              {@code false} to allow in-progress execution to finish
     * @return {@code true} if the task was successfully cancelled
     */
    public boolean cancel(boolean mayInterruptIfRunning) {
        // future.cancel(false) returns false for actively running tasks; for async tasks
        // the volatile cancelled flag signals the sleep loop to exit instead.
        boolean result = this.future.cancel(mayInterruptIfRunning) || (this.async && !this.future.isDone());

        if (result) {
            this.cancelled = true;
            this.repeating = false;
            this.running = false;
        }

        return result;
    }

    /**
     * Returns whether this task has completed, either normally, via cancellation, or
     * due to an exception (for one-shot tasks).
     *
     * @return {@code true} if the task is done or has been cancelled
     */
    public boolean isDone() {
        return this.cancelled || this.future.isDone();
    }

    /**
     * Returns whether this task was cancelled before it completed normally.
     *
     * @return {@code true} if the task has been cancelled
     * @see #cancel(boolean)
     */
    public boolean isCanceled() {
        return this.cancelled || this.future.isCancelled();
    }

    /**
     * Executes this task, dispatching to the appropriate execution strategy.
     * <p>
     * Synchronous tasks execute the wrapped {@link Runnable} once per invocation.
     * Asynchronous tasks handle their own initial delay and repeat loop internally
     * via {@link TimeUnit#sleep}.
     */
    @Override
    public void run() {
        if (this.async)
            this.runSelfScheduled();
        else
            this.executeTask();
    }

    /**
     * Manages the delay-sleep-execute-repeat loop for async virtual-thread tasks.
     */
    private void runSelfScheduled() {
        try {
            if (this.initialDelay > 0)
                this.timeUnit.sleep(this.initialDelay);

            do {
                if (this.cancelled) return;
                this.executeTask();

                if (this.repeating && !this.cancelled)
                    this.timeUnit.sleep(this.period);
            } while (this.repeating && !this.cancelled);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Executes the wrapped {@link Runnable}, tracking the {@link #running} state and
     * logging any exceptions. On success the {@link #consecutiveErrors} counter is
     * reset to zero; on failure it is incremented.
     */
    private void executeTask() {
        try {
            this.running = true;
            this.runnableTask.run();
            this.consecutiveErrors.set(0);
        } catch (Exception ex) {
            this.consecutiveErrors.incrementAndGet();
        } finally {
            this.running = false;
        }
    }

}
