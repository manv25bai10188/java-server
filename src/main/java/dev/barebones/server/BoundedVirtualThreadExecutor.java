package dev.barebones.server;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class BoundedVirtualThreadExecutor implements Executor, AutoCloseable {
    private final Semaphore workerPermits;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong threadSequence = new AtomicLong();
    private final String threadNamePrefix;

    public BoundedVirtualThreadExecutor(int maxVirtualThreads, String threadNamePrefix) {
        if (maxVirtualThreads < 1) {
            throw new IllegalArgumentException("Maximum virtual threads must be positive");
        }
        this.workerPermits = new Semaphore(maxVirtualThreads);
        this.threadNamePrefix = Objects.requireNonNull(threadNamePrefix, "Thread name prefix must not be null");
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "Command must not be null");
        if (closed.get()) {
            throw new RejectedExecutionException("Executor is closed");
        }

        if (!workerPermits.tryAcquire()) {
            command.run();
            return;
        }

        try {
            Thread.ofVirtual()
                    .name(threadNamePrefix + threadSequence.incrementAndGet())
                    .start(() -> {
                        try {
                            command.run();
                        } finally {
                            workerPermits.release();
                        }
                    });
        } catch (RuntimeException exception) {
            workerPermits.release();
            throw exception;
        }
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
