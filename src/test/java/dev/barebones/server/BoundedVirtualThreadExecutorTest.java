package dev.barebones.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class BoundedVirtualThreadExecutorTest {
    private BoundedVirtualThreadExecutorTest() {
    }

    public static void main(String[] args) throws Exception {
        boundsVirtualThreadsAndUsesCallerForOverflow();
        rejectsWorkAfterClose();
        System.out.println("Bounded virtual-thread executor tests passed");
    }

    private static void boundsVirtualThreadsAndUsesCallerForOverflow() throws Exception {
        BoundedVirtualThreadExecutor executor = new BoundedVirtualThreadExecutor(1, "test-worker-");
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicReference<Thread> workerThread = new AtomicReference<>();

        executor.execute(() -> {
            workerThread.set(Thread.currentThread());
            workerStarted.countDown();
            try {
                releaseWorker.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        require(workerStarted.await(2, TimeUnit.SECONDS), "virtual worker did not start");

        Thread caller = Thread.currentThread();
        AtomicReference<Thread> overflowThread = new AtomicReference<>();
        executor.execute(() -> overflowThread.set(Thread.currentThread()));
        require(workerThread.get().isVirtual(), "bounded worker was not virtual");
        require(overflowThread.get() == caller, "overflow task did not use caller thread");

        releaseWorker.countDown();
        executor.close();
    }

    private static void rejectsWorkAfterClose() {
        BoundedVirtualThreadExecutor executor = new BoundedVirtualThreadExecutor(1, "closed-worker-");
        executor.close();
        try {
            executor.execute(() -> { });
            throw new AssertionError("closed executor accepted work");
        } catch (RejectedExecutionException expected) {
            // Expected lifecycle rejection.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
