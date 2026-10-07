package com.shilapi.xcertplay.media;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Executes the production queue core on a desktop JVM; not an Android decoder simulation. */
public final class VideoQueueStressCheck {
    private static final class Job {
        final int size;
        final int id;
        final boolean reset;
        Job(int size, int id, boolean reset) { this.size = size; this.id = id; this.reset = reset; }
    }
    private static final Job RESET = new Job(-1, -1, true);
    private static Job frame(int size) { return new Job(size, 0, false); }
    private static Job control(int id) { return new Job(-1, id, false); }
    private static BoundedVideoJobQueue<Job> queue(int frames, int bytes) {
        return new BoundedVideoJobQueue<>(frames, bytes, j -> j.size, j -> j.reset, () -> RESET);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void same(Job expected, Job actual) { require(expected == actual, "Job order differs"); }

    private static void overflowKeepsControls() throws Exception {
        BoundedVideoJobQueue<Job> q = queue(2, 100);
        Job config = control(1), surface = control(2), latest = frame(1);
        q.offer(config); q.offer(frame(1)); q.offer(surface); q.offer(frame(1)); q.offer(latest);
        same(config, q.poll(0)); same(surface, q.poll(0)); same(RESET, q.poll(0)); same(latest, q.poll(0));
        same(null, q.poll(0));
    }

    private static void bytesAndDrainAccounting() throws Exception {
        BoundedVideoJobQueue<Job> q = queue(8, 5);
        Job newest = frame(3);
        q.offer(frame(3)); q.offer(newest);
        same(RESET, q.poll(0)); same(newest, q.poll(0));
        Job afterDrain = frame(5);
        q.offer(afterDrain); same(afterDrain, q.poll(0));
        q.offer(frame(6)); same(RESET, q.poll(0)); same(null, q.poll(0));
        q.offer(frame(2)); q.offer(control(10)); q.discardFrames();
        require(q.poll(0).id == 10, "Discard lost a control job");
        q.offer(frame(5)); require(q.poll(0).size == 5, "Discard did not reset byte accounting");
    }

    /** The old queue's externally observable rules, independently recomputed for every offer. */
    private static void referenceOffer(ArrayDeque<Job> q, Job job, int maxFrames, int maxBytes) {
        if (job.size >= 0) {
            long bytes = 0;
            int frames = 0;
            for (Job pending : q) if (pending.size >= 0) { frames++; bytes += pending.size; }
            if (frames >= maxFrames || bytes + job.size > maxBytes) {
                q.removeIf(j -> j.size >= 0 || j.reset);
                q.addLast(RESET);
            }
            if (job.size > maxBytes) return;
        }
        q.addLast(job);
    }

    private static void randomizedReferenceEquivalence() throws Exception {
        BoundedVideoJobQueue<Job> q = queue(7, 31);
        ArrayDeque<Job> reference = new ArrayDeque<>();
        Random random = new Random(2829);
        for (int i = 0; i < 100_000; i++) {
            int operation = random.nextInt(7);
            if (operation <= 4) {
                Job job = operation == 4 ? control(i) : frame(random.nextInt(48));
                q.offer(job); referenceOffer(reference, job, 7, 31);
            } else if (operation == 5) same(reference.pollFirst(), q.poll(0));
            else { q.discardFrames(); reference.removeIf(j -> j.size >= 0 || j.reset); }
        }
        while (!reference.isEmpty()) same(reference.pollFirst(), q.poll(0));
        same(null, q.poll(0));
    }

    private static void blockedDecoderWakesForOversizedFrame() throws Exception {
        BoundedVideoJobQueue<Job> q = queue(8, 5);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        try {
            Future<Job> job = worker.submit(() -> { entered.countDown(); return q.poll(2_000); });
            require(entered.await(1, TimeUnit.SECONDS), "Consumer did not start");
            q.offer(frame(6));
            same(RESET, job.get(1, TimeUnit.SECONDS));
            same(null, q.poll(0));
        } finally { worker.shutdownNow(); }
    }

    private static void interruptionUnblocksPoll() throws Exception {
        BoundedVideoJobQueue<Job> q = queue(8, 32);
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            entered.countDown();
            try { q.poll(30_000); }
            catch (InterruptedException expected) { interrupted.countDown(); }
        }, "queue-interrupt-check");
        worker.setDaemon(true);
        worker.start();
        require(entered.await(1, TimeUnit.SECONDS), "Consumer did not start");
        worker.interrupt();
        require(interrupted.await(1, TimeUnit.SECONDS), "Poll ignored interruption");
        worker.join(1_000);
        require(!worker.isAlive(), "Consumer did not exit");
        Job next = frame(1); q.offer(next); same(next, q.poll(0));
    }

    private static void concurrentProducersPreserveEveryControl() throws Exception {
        final int producers = 4, framesPerProducer = 60_000, controlsPerProducer = 300;
        BoundedVideoJobQueue<Job> q = queue(60, 8_192);
        ExecutorService workers = Executors.newFixedThreadPool(producers + 1);
        AtomicInteger finished = new AtomicInteger();
        Set<Integer> controls = new HashSet<>();
        try {
            Future<?> consumer = workers.submit(() -> {
                try {
                    while (true) {
                        Job job = q.poll(5);
                        if (job == null) { if (finished.get() == producers) break; else continue; }
                        if (job.size < 0 && !job.reset) require(controls.add(job.id), "Control delivered twice");
                    }
                } catch (InterruptedException error) { throw new AssertionError(error); }
            });
            Future<?>[] tasks = new Future<?>[producers];
            for (int p = 0; p < producers; p++) {
                final int producer = p;
                tasks[p] = workers.submit(() -> {
                    try {
                        for (int i = 0; i < framesPerProducer; i++) {
                            q.offer(frame(64 + i % 193));
                            if (i % 200 == 0) q.offer(control(producer * controlsPerProducer + i / 200));
                            if (i % 997 == 0) q.discardFrames();
                        }
                    } finally { finished.incrementAndGet(); }
                });
            }
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
            consumer.get(10, TimeUnit.SECONDS);
            require(controls.size() == producers * controlsPerProducer, "Lost configuration/surface control jobs");
            same(null, q.poll(0));
        } finally { workers.shutdownNow(); }
    }

    public static void main(String[] args) throws Exception {
        overflowKeepsControls(); bytesAndDrainAccounting(); randomizedReferenceEquivalence();
        blockedDecoderWakesForOversizedFrame(); interruptionUnblocksPoll(); concurrentProducersPreserveEveryControl();
        System.out.println("{\"status\":\"passed\",\"checks\":6,\"random_operations\":100000,\"concurrent_frames\":240000,\"control_jobs_verified\":1200,\"android_decoder_exercised\":false}");
    }
}
