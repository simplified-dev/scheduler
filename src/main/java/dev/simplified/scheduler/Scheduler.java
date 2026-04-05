package dev.simplified.scheduler;

import dev.simplified.collection.concurrent.Concurrent;
import dev.simplified.collection.concurrent.ConcurrentList;
import dev.simplified.collection.concurrent.ConcurrentSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A dual-executor task scheduler that supports serial (synchronous) and virtual-thread
 * (asynchronous) execution of one-shot and repeating {@link ScheduledTask}s.
 * <p>
 * Internally the scheduler maintains two executors:
 * <ul>
 *   <li><b>syncExecutor</b> - a single-threaded {@link ScheduledExecutorService} for serial tasks;
 *       only one synchronous task runs at a time, making it safe for lightweight,
 *       ordering-sensitive work.</li>
 *   <li><b>virtualExecutor</b> - a virtual-thread-per-task {@link ExecutorService} for all
 *       asynchronous tasks. Delayed and repeating async tasks manage their own timing
 *       internally via {@link TimeUnit#sleep}, yielding the carrier thread during waits
 *       so that no platform resources are consumed while idle.</li>
 * </ul>
 * A background cleaner task runs on the sync executor every 30 seconds to purge completed
 * tasks from the internal task list. The cleaner is cancelled automatically on {@link #shutdown()}.
 * <p>
 * The {@link Executor} contract is implemented by delegating {@link #execute(Runnable)} to
 * the virtual thread executor, so this scheduler can be passed anywhere an {@code Executor}
 * is expected.
 *
 * @see ScheduledTask
 */
public final class Scheduler implements Executor {

    /** Single-threaded executor for synchronous (serial) task scheduling. */
    private final @NotNull ScheduledExecutorService syncExecutor;

    /** Virtual-thread executor for all asynchronous tasks. */
    private final @NotNull ExecutorService virtualExecutor;

    /** All user-submitted tasks tracked by this scheduler. */
    private final @NotNull ConcurrentList<ScheduledTask> tasks = Concurrent.newList();

    /** Internal cleaner task that periodically removes completed tasks from {@link #tasks}. */
    private final @NotNull ScheduledTask cleanerTask;

    /**
     * Creates a new scheduler with a single-threaded sync executor and a virtual-thread
     * async executor.
     */
    public Scheduler() {
        this.syncExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            thread.setName("scheduler-sync");
            return thread;
        });
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // Schedule Permanent Cleaner
        this.cleanerTask = new ScheduledTask(
            this.syncExecutor,
            () -> this.tasks.removeIf(ScheduledTask::isDone),
            1_000,
            30_000,
            TimeUnit.MILLISECONDS,
            false
        );

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "scheduler-shutdown"));
    }

    /**
     * Returns the set of non-daemon, non-JVM threads that are still alive.
     * <p>
     * Useful for diagnosing thread leaks after shutdown. Filters out JVM-internal threads
     * ({@code main}, {@code Reference Handler}, {@code Signal Dispatcher}, {@code Notification Thread},
     * {@code Finalizer}), Gradle worker threads, and test worker threads.
     *
     * @return an unmodifiable set of leaked non-daemon threads
     */
    public static @NotNull ConcurrentSet<Thread> leakedThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(t -> !t.isDaemon() && t.isAlive())
            .filter(t -> !t.getName().equals("main"))
            .filter(t -> !t.getName().startsWith("Reference Handler"))
            .filter(t -> !t.getName().startsWith("Signal Dispatcher"))
            .filter(t -> !t.getName().startsWith("Notification"))
            .filter(t -> !t.getName().startsWith("Finalizer"))
            .filter(t -> !t.getName().contains("workers"))
            .filter(t -> !t.getName().startsWith("Test worker"))
            .collect(Concurrent.toUnmodifiableSet());
    }

    /**
     * Causes the calling thread to sleep for the specified number of milliseconds.
     * <p>
     * If the thread is interrupted during sleep, the interruption is silently swallowed.
     *
     * @param millis the duration to sleep in milliseconds
     * @throws IllegalArgumentException if {@code millis} is negative
     */
    public static void sleep(@Range(from = 0, to = Long.MAX_VALUE) long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignore) { }
    }

    /**
     * Cancels the task with the given id without interrupting a running execution.
     * <p>
     * Equivalent to {@code cancel(id, false)}.
     *
     * @param id the {@linkplain ScheduledTask#getId() task id} to cancel
     * @see #cancel(long, boolean)
     */
    public void cancel(@Range(from = 1, to = Long.MAX_VALUE) long id) {
        this.cancel(id, false);
    }

    /**
     * Cancels the task with the given id, optionally interrupting a running execution.
     * <p>
     * If no task with the specified id exists in the task list, this method is a no-op.
     *
     * @param id the {@linkplain ScheduledTask#getId() task id} to cancel
     * @param mayInterruptIfRunning {@code true} to interrupt the executing thread
     */
    public void cancel(@Range(from = 1, to = Long.MAX_VALUE) long id, boolean mayInterruptIfRunning) {
        this.tasks.stream()
            .filter(scheduledTask -> scheduledTask.getId() == id)
            .findFirst()
            .ifPresent(scheduledTask -> scheduledTask.cancel(mayInterruptIfRunning));
    }

    /**
     * Cancels the given task without interrupting a running execution.
     * <p>
     * Equivalent to {@code cancel(task, false)}.
     *
     * @param task the task to cancel
     * @see #cancel(ScheduledTask, boolean)
     */
    public void cancel(@NotNull ScheduledTask task) {
        this.cancel(task, false);
    }

    /**
     * Cancels the given task, optionally interrupting a running execution.
     *
     * @param task the task to cancel
     * @param mayInterruptIfRunning {@code true} to interrupt the executing thread
     * @see ScheduledTask#cancel(boolean)
     */
    public void cancel(@NotNull ScheduledTask task, boolean mayInterruptIfRunning) {
        task.cancel(mayInterruptIfRunning);
    }

    /**
     * Returns an unmodifiable snapshot of all tracked tasks.
     *
     * @return an unmodifiable {@link ConcurrentList} of currently tracked tasks
     */
    public @NotNull ConcurrentList<ScheduledTask> getTasks() {
        return Concurrent.newUnmodifiableList(this.tasks);
    }

    /**
     * Schedules a repeating synchronous task with no initial delay and a 50-millisecond period.
     * <p>
     * <b>Warning:</b> synchronous tasks share a single thread - avoid heavy or blocking work.
     *
     * @param task the work to execute
     * @return the scheduled task handle
     */
    public @NotNull ScheduledTask repeat(@NotNull Runnable task) {
        return this.schedule(task, 0, 50);
    }

    /**
     * Schedules a repeating asynchronous task with no initial delay and a 50-millisecond period.
     *
     * @param task the work to execute
     * @return the scheduled task handle
     */
    public @NotNull ScheduledTask repeatAsync(@NotNull Runnable task) {
        return this.scheduleAsync(task, 0, 50);
    }

    /**
     * Schedules a one-shot synchronous task for immediate execution.
     *
     * @param task the work to execute
     * @return the scheduled task handle
     */
    public @NotNull ScheduledTask schedule(@NotNull Runnable task) {
        return this.schedule(task, 0);
    }

    /**
     * Schedules a one-shot synchronous task with the specified delay in milliseconds.
     *
     * @param task the work to execute
     * @param delay the delay in milliseconds before execution
     * @return the scheduled task handle
     */
    public @NotNull ScheduledTask schedule(@NotNull Runnable task, @Range(from = 0, to = Long.MAX_VALUE) long delay) {
        return this.schedule(task, delay, 0);
    }

    /**
     * Schedules a synchronous task with the specified delays in milliseconds.
     * <p>
     * If {@code repeatDelay} is {@code 0} the task runs once; otherwise it repeats with a
     * fixed delay between the end of one execution and the start of the next.
     *
     * @param task the work to execute
     * @param initialDelay the delay in milliseconds before the first execution
     * @param repeatDelay the delay in milliseconds between executions ({@code 0} for one-shot)
     * @return the scheduled task handle
     */
    public @NotNull ScheduledTask schedule(@NotNull Runnable task, @Range(from = 0, to = Long.MAX_VALUE) long initialDelay, @Range(from = 0, to = Long.MAX_VALUE) long repeatDelay) {
        return this.schedule(task, initialDelay, repeatDelay, TimeUnit.MILLISECONDS);
    }

    /**
     * Schedules a synchronous task with the specified delays and time unit.
     * <p>
     * If {@code repeatDelay} is {@code 0} the task runs once; otherwise it repeats with a
     * fixed delay between the end of one execution and the start of the next.
     *
     * @param task the work to execute
     * @param initialDelay the delay before the first execution
     * @param repeatDelay the delay between executions ({@code 0} for one-shot)
     * @param timeUnit the time unit for {@code initialDelay} and {@code repeatDelay}
     * @return the scheduled task handle
     */
    public @NotNull ScheduledTask schedule(@NotNull Runnable task, @Range(from = 0, to = Long.MAX_VALUE) long initialDelay, @Range(from = 0, to = Long.MAX_VALUE) long repeatDelay, @NotNull TimeUnit timeUnit) {
        return this.scheduleTask(task, initialDelay, repeatDelay, timeUnit, false);
    }

    /**
     * Schedules a one-shot asynchronous task for immediate execution.
     *
     * @param task the work to execute
     * @return the scheduled task handle
     */
    public @NotNull ScheduledTask scheduleAsync(@NotNull Runnable task) {
        return this.scheduleAsync(task, 0);
    }

    /**
     * Schedules a one-shot asynchronous task with the specified delay in milliseconds.
     *
     * @param task the work to execute
     * @param initialDelay the delay in milliseconds before execution
     * @return the scheduled task handle
     */
    public @NotNull ScheduledTask scheduleAsync(@NotNull Runnable task, @Range(from = 0, to = Long.MAX_VALUE) long initialDelay) {
        return this.scheduleAsync(task, initialDelay, 0);
    }

    /**
     * Schedules an asynchronous task with the specified delays in milliseconds.
     * <p>
     * If {@code repeatDelay} is {@code 0} the task runs once; otherwise it repeats with a
     * fixed delay between the end of one execution and the start of the next.
     *
     * @param task the work to execute
     * @param initialDelay the delay in milliseconds before the first execution
     * @param repeatDelay the delay in milliseconds between executions ({@code 0} for one-shot)
     * @return the scheduled task handle
     */
    public @NotNull ScheduledTask scheduleAsync(@NotNull Runnable task, @Range(from = 0, to = Long.MAX_VALUE) long initialDelay, @Range(from = 0, to = Long.MAX_VALUE) long repeatDelay) {
        return this.scheduleAsync(task, initialDelay, repeatDelay, TimeUnit.MILLISECONDS);
    }

    /**
     * Schedules an asynchronous task with the specified delays and time unit.
     * <p>
     * If {@code repeatDelay} is {@code 0} the task runs once; otherwise it repeats with a
     * fixed delay between the end of one execution and the start of the next.
     *
     * @param task the work to execute
     * @param initialDelay the delay before the first execution
     * @param repeatDelay the delay between executions ({@code 0} for one-shot)
     * @param timeUnit the time unit for {@code initialDelay} and {@code repeatDelay}
     * @return the scheduled task handle
     */
    public @NotNull ScheduledTask scheduleAsync(@NotNull Runnable task, @Range(from = 0, to = Long.MAX_VALUE) long initialDelay, @Range(from = 0, to = Long.MAX_VALUE) long repeatDelay, @NotNull TimeUnit timeUnit) {
        return this.scheduleTask(task, initialDelay, repeatDelay, timeUnit, true);
    }

    private @NotNull ScheduledTask scheduleTask(@NotNull Runnable task, @Range(from = 0, to = Long.MAX_VALUE) long initialDelay, @Range(from = 0, to = Long.MAX_VALUE) long repeatDelay, @NotNull TimeUnit timeUnit, boolean async) {
        ScheduledTask scheduledTask = new ScheduledTask(
            async ? this.virtualExecutor : this.syncExecutor,
            task,
            initialDelay,
            repeatDelay,
            timeUnit,
            async
        );

        this.tasks.add(scheduledTask);
        return scheduledTask;
    }

    /**
     * Initiates an orderly shutdown of this scheduler.
     * <p>
     * The internal cleaner task is cancelled first, then both executors (sync and virtual)
     * are shut down. Previously submitted tasks will still run to completion, but no new
     * tasks will be accepted.
     *
     * @see #isShutdown()
     * @see #isTerminated()
     */
    public void shutdown() {
        this.cleanerTask.cancel(true);
        this.tasks.forEach(task -> task.cancel(true));
        this.syncExecutor.shutdownNow();
        this.virtualExecutor.shutdownNow();
    }

    /**
     * Returns whether both executors have been shut down.
     *
     * @return {@code true} if the sync and virtual executors have both been shut down
     * @see #shutdown()
     */
    public boolean isShutdown() {
        return this.syncExecutor.isShutdown() && this.virtualExecutor.isShutdown();
    }

    /**
     * Returns whether both executors have terminated after shutdown.
     * <p>
     * A terminated executor has completed all submitted tasks and released its threads.
     *
     * @return {@code true} if the sync and virtual executors have both terminated
     * @see #shutdown()
     */
    public boolean isTerminated() {
        return this.syncExecutor.isTerminated() && this.virtualExecutor.isTerminated();
    }

    /**
     * Submits a command for execution on the virtual thread executor.
     * <p>
     * This method satisfies the {@link Executor} contract, allowing this scheduler to be
     * used wherever an {@code Executor} is expected. Tasks run on virtual threads, making
     * this ideal for I/O-bound work.
     *
     * @param command the runnable task to execute
     */
    @Override
    public void execute(@NotNull Runnable command) {
        this.virtualExecutor.execute(command);
    }

}
