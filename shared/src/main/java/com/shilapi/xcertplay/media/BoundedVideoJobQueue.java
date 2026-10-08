package com.shilapi.xcertplay.media;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/** Atomic frame accounting without an allocation and queue scan for every incoming picture. */
final class BoundedVideoJobQueue<T> {
    private final ArrayDeque<T> jobs = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final int maxFrames;
    private final long maxBytes;
    private final ToIntFunction<T> frameBytes;
    private final Predicate<T> isResync;
    private final Supplier<T> resync;
    private int frames;
    private long bytes;

    BoundedVideoJobQueue(int maxFrames, int maxBytes, ToIntFunction<T> frameBytes,
            Predicate<T> isResync, Supplier<T> resync) {
        if (maxFrames <= 0 || maxBytes <= 0) throw new IllegalArgumentException("Positive video budgets required");
        this.maxFrames = maxFrames;
        this.maxBytes = maxBytes;
        this.frameBytes = frameBytes;
        this.isResync = isResync;
        this.resync = resync;
    }

    void offer(T job) {
        int size = frameBytes.applyAsInt(job);
        lock.lock();
        try {
            if (size >= 0 && (frames >= maxFrames || bytes + size > maxBytes)) {
                discardFramesLocked();
                jobs.addLast(resync.get());
            }
            // Even rejecting a single oversized frame must notify a blocked decoder of Resync.
            if (size <= maxBytes) {
                jobs.addLast(job);
                if (size >= 0) { frames++; bytes += size; }
            }
            available.signal();
        } finally { lock.unlock(); }
    }

    T poll(long timeoutMillis) throws InterruptedException {
        long remaining = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        lock.lockInterruptibly();
        try {
            while (jobs.isEmpty()) {
                if (remaining <= 0L) return null;
                remaining = available.awaitNanos(remaining);
            }
            T job = jobs.removeFirst();
            int size = frameBytes.applyAsInt(job);
            if (size >= 0) { frames--; bytes -= size; }
            if (!jobs.isEmpty()) available.signal();
            return job;
        } finally { lock.unlock(); }
    }

    void discardFrames() {
        lock.lock();
        try { discardFramesLocked(); }
        finally { lock.unlock(); }
    }

    /** Shutdown must collect detach acknowledgements even with the interrupt flag set. */
    List<T> drain() {
        lock.lock();
        try {
            List<T> pending = new ArrayList<>(jobs);
            jobs.clear();
            frames = 0;
            bytes = 0;
            return pending;
        } finally { lock.unlock(); }
    }

    private void discardFramesLocked() {
        Iterator<T> iterator = jobs.iterator();
        while (iterator.hasNext()) {
            T job = iterator.next();
            if (frameBytes.applyAsInt(job) >= 0 || isResync.test(job)) iterator.remove();
        }
        frames = 0;
        bytes = 0;
    }
}
