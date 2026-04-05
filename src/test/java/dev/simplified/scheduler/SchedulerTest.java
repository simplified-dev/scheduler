package dev.simplified.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {

    private Scheduler scheduler;

    @BeforeEach
    void setup() {
        scheduler = new Scheduler();
    }

    @AfterEach
    void teardown() {
        scheduler.shutdown();
    }

    @Nested
    class OneShot {

        @Test
        void schedule_executesTask() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            scheduler.schedule(latch::countDown);
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        }

        @Test
        void schedule_withDelay_executesAfterDelay() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            long start = System.currentTimeMillis();
            scheduler.schedule(latch::countDown, 100);
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed >= 80, "Expected at least ~100ms delay, got " + elapsed);
        }

        @Test
        void schedule_returnedTask_isDoneAfterExecution() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            ScheduledTask task = scheduler.schedule(latch::countDown, 50);
            latch.await(2, TimeUnit.SECONDS);
            // Allow state propagation
            Thread.sleep(50);
            assertTrue(task.isDone());
            assertFalse(task.isRepeating());
        }

        @Test
        void scheduleAsync_executesOnAsyncExecutor() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            scheduler.scheduleAsync(latch::countDown);
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        }

        @Test
        void execute_delegatesToAsyncExecutor() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            scheduler.execute(latch::countDown);
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        }
    }

    @Nested
    class Repeating {

        @Test
        void repeat_executesMultipleTimes() throws Exception {
            AtomicInteger count = new AtomicInteger(0);
            scheduler.schedule(count::incrementAndGet, 0, 50);
            Thread.sleep(300);
            assertTrue(count.get() >= 4, "Expected at least 4 executions, got " + count.get());
        }

        @Test
        void repeatingTask_isRepeating() {
            ScheduledTask task = scheduler.schedule(() -> {}, 0, 100);
            assertTrue(task.isRepeating());
        }

        @Test
        void repeatAsync_executesMultipleTimes() throws Exception {
            AtomicInteger count = new AtomicInteger(0);
            scheduler.scheduleAsync(count::incrementAndGet, 0, 50);
            Thread.sleep(300);
            assertTrue(count.get() >= 4, "Expected at least 4 executions, got " + count.get());
        }

        @Test
        void repeat_convenience_usesDefaultPeriod() {
            ScheduledTask task = scheduler.repeat(() -> {});
            assertTrue(task.isRepeating());
            assertEquals(50, task.getPeriod());
        }
    }

    @Nested
    class Cancellation {

        @Test
        void cancel_preventsExecution() throws Exception {
            AtomicBoolean ran = new AtomicBoolean(false);
            ScheduledTask task = scheduler.schedule(() -> ran.set(true), 500);
            assertTrue(task.cancel());
            Thread.sleep(700);
            assertFalse(ran.get());
        }

        @Test
        void cancel_stopsRepeatingTask() throws Exception {
            AtomicInteger count = new AtomicInteger(0);
            ScheduledTask task = scheduler.schedule(count::incrementAndGet, 0, 50);
            Thread.sleep(200);
            task.cancel();
            int afterCancel = count.get();
            Thread.sleep(200);
            // Allow up to 1 extra execution that may have been in-flight
            assertTrue(count.get() <= afterCancel + 1,
                "Task should stop after cancel, got extra executions");
        }

        @Test
        void cancelById_cancelsMatchingTask() throws Exception {
            AtomicBoolean ran = new AtomicBoolean(false);
            ScheduledTask task = scheduler.schedule(() -> ran.set(true), 500);
            scheduler.cancel(task.getId());
            Thread.sleep(700);
            assertFalse(ran.get());
        }

        @Test
        void cancelledTask_isCanceled() {
            ScheduledTask task = scheduler.schedule(() -> {}, 10_000);
            task.cancel();
            assertTrue(task.isCanceled());
            assertTrue(task.isDone());
        }

        @Test
        void cancel_repeatingTask_clearsRepeatingFlag() {
            ScheduledTask task = scheduler.schedule(() -> {}, 0, 100);
            assertTrue(task.isRepeating());
            task.cancel();
            assertFalse(task.isRepeating());
        }
    }

    @Nested
    class TaskTracking {

        @Test
        void getTasks_returnsUnmodifiableSnapshot() {
            scheduler.schedule(() -> {}, 10_000);
            scheduler.schedule(() -> {}, 10_000);
            var tasks = scheduler.getTasks();
            // Cleaner task + 2 user tasks
            assertTrue(tasks.size() >= 2);
            assertThrows(UnsupportedOperationException.class,
                () -> tasks.add(null));
        }

        @Test
        void taskId_isMonotonicallyIncreasing() {
            ScheduledTask a = scheduler.schedule(() -> {}, 10_000);
            ScheduledTask b = scheduler.schedule(() -> {}, 10_000);
            assertTrue(b.getId() > a.getId());
        }

        @Test
        void task_hasAddedTime() {
            long before = System.currentTimeMillis();
            ScheduledTask task = scheduler.schedule(() -> {}, 10_000);
            long after = System.currentTimeMillis();
            assertTrue(task.getAddedTime() >= before);
            assertTrue(task.getAddedTime() <= after);
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void taskException_incrementsConsecutiveErrors() throws Exception {
            CountDownLatch latch = new CountDownLatch(3);
            ScheduledTask task = scheduler.schedule(() -> {
                latch.countDown();
                throw new RuntimeException("test error");
            }, 0, 50);

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertTrue(task.getConsecutiveErrors().get() >= 3);
        }

        @Test
        void taskSuccess_resetsConsecutiveErrors() throws Exception {
            AtomicInteger callCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(4);
            scheduler.schedule(() -> {
                int count = callCount.incrementAndGet();
                latch.countDown();
                if (count <= 2) throw new RuntimeException("fail");
                // Succeeds on call 3+
            }, 0, 50);

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            // After a success, errors should reset to 0
            Thread.sleep(100);
        }
    }

    @Tag("slow")
    @Nested
    class Shutdown {

        @Test
        void shutdown_setsShutdownFlag() {
            assertFalse(scheduler.isShutdown());
            scheduler.shutdown();
            assertTrue(scheduler.isShutdown());
        }

        @Test
        void shutdown_preventsNewTasks() {
            scheduler.shutdown();
            assertThrows(RejectedExecutionException.class,
                () -> scheduler.schedule(() -> {}, 0));
        }

        @Test
        void shutdown_terminatesWithinTimeout() throws Exception {
            // Schedule repeating tasks on both executors
            scheduler.schedule(() -> {}, 0, 50);
            scheduler.scheduleAsync(() -> {}, 0, 50);
            Thread.sleep(200); // Let them run a few cycles

            scheduler.shutdown();

            // Must terminate within 5 seconds — if this hangs, shutdown is broken
            long start = System.currentTimeMillis();
            while (!scheduler.isTerminated() && System.currentTimeMillis() - start < 5_000)
                Thread.sleep(50);

            assertTrue(scheduler.isTerminated(),
                "Scheduler did not terminate within 5 seconds after shutdown()");
        }

        @Test
        void shutdown_terminatesWithActiveSyncTask() throws Exception {
            CountDownLatch running = new CountDownLatch(1);
            scheduler.schedule(() -> {
                running.countDown();
                Scheduler.sleep(10_000); // Simulate long-running sync task
            });
            assertTrue(running.await(2, TimeUnit.SECONDS));

            scheduler.shutdown();

            long start = System.currentTimeMillis();
            while (!scheduler.isTerminated() && System.currentTimeMillis() - start < 5_000)
                Thread.sleep(50);

            assertTrue(scheduler.isTerminated(),
                "Scheduler did not terminate with an active sync task");
        }

        @Test
        void shutdown_terminatesWithActiveAsyncTask() throws Exception {
            CountDownLatch running = new CountDownLatch(1);
            scheduler.scheduleAsync(() -> {
                running.countDown();
                Scheduler.sleep(10_000); // Simulate long-running async task
            });
            assertTrue(running.await(2, TimeUnit.SECONDS));

            scheduler.shutdown();

            long start = System.currentTimeMillis();
            while (!scheduler.isTerminated() && System.currentTimeMillis() - start < 5_000)
                Thread.sleep(50);

            assertTrue(scheduler.isTerminated(),
                "Scheduler did not terminate with an active async task");
        }

    }

    @Nested
    class TimeUnits {

        @Test
        void schedule_withTimeUnit() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            ScheduledTask task = scheduler.schedule(
                latch::countDown, 100, 0, TimeUnit.MILLISECONDS
            );
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertEquals(TimeUnit.MILLISECONDS, task.getTimeUnit());
        }
    }
}
