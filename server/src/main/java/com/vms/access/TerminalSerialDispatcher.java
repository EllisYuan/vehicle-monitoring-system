package com.vms.access;

import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** 按 terminal_no 串行并共享有界业务线程池，避免不同连接绕过终端级顺序。 */
@Component
public class TerminalSerialDispatcher {
    private static final int WORKER_COUNT = 4;
    private static final int WORK_QUEUE_CAPACITY = 256;
    private static final int TERMINAL_QUEUE_CAPACITY = 64;

    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            WORKER_COUNT, WORKER_COUNT, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(WORK_QUEUE_CAPACITY),
            runnable -> new Thread(runnable, "terminal-business-" + THREADS.incrementAndGet()),
            new ThreadPoolExecutor.AbortPolicy());
    private static final AtomicInteger THREADS = new AtomicInteger();
    private final ConcurrentMap<String, SerialQueue> queues = new ConcurrentHashMap<>();

    public void execute(String terminalNo, Runnable task, Runnable rejected) {
        if (terminalNo == null || terminalNo.isBlank()) {
            throw new RejectedExecutionException("terminal key is missing");
        }
        queues.computeIfAbsent(terminalNo, SerialQueue::new).enqueue(task, rejected);
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        workers.shutdownNow();
        queues.clear();
    }

    private final class SerialQueue {
        private final String terminalNo;
        private final ArrayDeque<Job> pending = new ArrayDeque<>();
        private boolean running;

        private SerialQueue(String terminalNo) {
            this.terminalNo = terminalNo;
        }

        private synchronized void enqueue(Runnable task, Runnable rejected) {
            if (pending.size() >= TERMINAL_QUEUE_CAPACITY) {
                throw new RejectedExecutionException("terminal queue is full: " + terminalNo);
            }
            pending.addLast(new Job(task, rejected));
            if (!running) {
                running = true;
                try {
                    workers.execute(this::runNext);
                } catch (RejectedExecutionException exception) {
                    pending.removeLast();
                    running = false;
                    removeIfIdle();
                    throw exception;
                }
            }
        }

        private void runNext() {
            Job job;
            synchronized (this) {
                job = pending.peekFirst();
                if (job == null) {
                    running = false;
                    removeIfIdle();
                    return;
                }
            }
            try {
                job.task().run();
            } finally {
                scheduleNext();
            }
        }

        private void scheduleNext() {
            Job rejectedHead = null;
            synchronized (this) {
                pending.removeFirst();
                if (pending.isEmpty()) {
                    running = false;
                    removeIfIdle();
                    return;
                }
                try {
                    workers.execute(this::runNext);
                } catch (RejectedExecutionException exception) {
                    rejectedHead = pending.removeFirst();
                    while (!pending.isEmpty()) {
                        pending.removeFirst().rejected().run();
                    }
                    running = false;
                    removeIfIdle();
                }
            }
            if (rejectedHead != null) {
                rejectedHead.rejected().run();
            }
        }

        private void removeIfIdle() {
            if (!running && pending.isEmpty()) {
                queues.remove(terminalNo, this);
            }
        }
    }

    private record Job(Runnable task, Runnable rejected) { }
}
