package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.Skein;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;

/**
 * The worker pool, deliberately hand-rolled rather than a {@link
 * java.util.concurrent.ForkJoinPool}.
 *
 * <p>Two requirements rule the standard pools out. First, the submitting thread
 * has to <em>help</em>: a server with two dimensions and eight workers would
 * otherwise leave the server thread idle at the barrier while one worker does
 * all the work. Second, tasks carry level ownership, so any thread must be able
 * to run any task -- which the tasks themselves guarantee by entering and
 * leaving {@link TickContext} around their own body.
 *
 * <p>Each batch publishes up to one runner per worker plus the coordinator.
 * Runners claim task indices from a shared cursor, keeping the entire batch
 * available to every helper without allocating a queue node and wrapper per
 * task. Callers submit the longest task first, so the pile drains evenly.
 *
 * <p>Both waits -- the idle worker's and the coordinator's at the barrier -- spin
 * briefly before parking. A tick is a few milliseconds of work split into pieces
 * that often finish microseconds apart, and a barrier that parks immediately
 * pays a wakeup for every one of them. When the spin loses the race the park is
 * timed rather than indefinite, but the task that empties a batch also unparks
 * whoever is waiting on it: a pass whose tail is one long cell would otherwise
 * be noticed only when that timer next expired, well after the work stopped.
 */
public final class SkeinPool {
    /** Roughly a microsecond of spinning before a waiter gives up and parks. */
    private static final int SPINS = 256;

    /**
     * How long a barrier may stay open before the pool assumes something is
     * wrong and says so.
     *
     * <p>A stalled barrier is the worst failure this design has, because it
     * looks exactly like the server hanging: no crash, no log, nothing to go
     * on. One thread that never returns from a task takes the whole tick loop
     * with it. Ten seconds is far longer than any legitimate tick, so crossing
     * it means a dump of every worker's stack is worth more than the silence.
     */
    private static final long STALL_NANOS = 10_000_000_000L;

    /**
     * One {@link #invokeAll} batch: how many runners are still outstanding,
     * which task to claim next, and who is waiting for every runner to exit.
     *
     * <p>The waiter is fixed before the tasks are queued, so a worker that
     * finishes the last one always has somebody to unpark and no completion can
     * fall between the two. The coordinator's park stays timed regardless --
     * losing the signal then costs latency rather than the barrier.
     */
    private static final class Batch implements Runnable {
        final AtomicInteger outstanding;
        final AtomicInteger next = new AtomicInteger();
        final Thread waiter;
        final int count;
        final IntConsumer action;
        private Throwable failure;

        Batch(int count, int runners, IntConsumer action, Thread waiter) {
            this.outstanding = new AtomicInteger(runners);
            this.count = count;
            this.action = action;
            this.waiter = waiter;
        }

        @Override
        public void run() {
            try {
                for (int index; (index = this.next.getAndIncrement()) < this.count;) {
                    try {
                        this.action.accept(index);
                    } catch (Throwable error) {
                        // Keep the barrier closed until every runner exits,
                        // even if a task throws outside its phase's handler.
                        failed(error);
                    }
                }
            } finally {
                finished();
            }
        }

        private synchronized void failed(Throwable error) {
            if (this.failure == null) {
                this.failure = error;
            }
        }

        void rethrowFailure() {
            // The outstanding counter publishes every runner's writes.
            if (this.failure instanceof RuntimeException error) throw error;
            if (this.failure instanceof Error error) throw error;
            if (this.failure != null) throw new RuntimeException(this.failure);
        }

        void finished() {
            if (this.outstanding.decrementAndGet() == 0 && this.waiter != Thread.currentThread()) {
                // Not when the coordinator ran the last task itself: it is about
                // to read the counter anyway, and the permit would only be
                // swallowed by the next barrier's first park.
                LockSupport.unpark(this.waiter);
            }
        }
    }

    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final TickContext.Worker[] workers;
    private volatile boolean running = true;

    public SkeinPool(int threads) {
        this.workers = new TickContext.Worker[threads];
        for (int i = 0; i < threads; i++) {
            // "Server" in the name: Cupboard queues an entity added from any thread whose name
            // lacks it until the next add on one that has it, which under Skein may never come.
            TickContext.Worker worker = new TickContext.Worker(this::workerLoop, "Skein Server Worker #" + i);
            this.workers[i] = worker;
            worker.start();
        }
    }

    public int size() {
        return this.workers.length;
    }

    private void workerLoop() {
        while (this.running) {
            Runnable task = this.queue.poll();
            if (task == null) {
                awaitWork();
                continue;
            }
            try {
                task.run();
            } catch (Throwable t) {
                // A task that throws past its own handler must not take the
                // worker down with it, or the pool silently shrinks to nothing
                // and the barrier waits forever.
                Skein.LOGGER.error("Skein worker caught an unhandled throwable", t);
            }
        }
    }

    private void awaitWork() {
        for (int i = 0; i < SPINS; i++) {
            if (!this.queue.isEmpty() || !this.running) {
                return;
            }
            Thread.onSpinWait();
        }
        TickContext.Worker self = (TickContext.Worker) Thread.currentThread();
        // Published before the queue is re-checked, and read by wake() after the
        // tasks are enqueued. Whichever of the two goes second sees the other,
        // so a worker cannot sleep through a batch it was told about.
        self.parked = true;
        try {
            if (this.queue.isEmpty() && this.running) {
                // Timed anyway: a permit left over from an earlier wakeup would
                // otherwise be the only thing standing between this worker and a
                // batch, and 50ms of idle latency is invisible next to a tick.
                LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(50));
            }
        } finally {
            self.parked = false;
        }
    }

    /**
     * Runs every task and returns once all of them have finished.
     *
     * <p>The caller participates, so a batch never waits on threads that are
     * busy elsewhere -- with two dimensions and eight workers the server thread
     * would otherwise sit at the barrier while one worker did all the work.
     *
     * <p>It participates in its own batch and nobody else's. Taking whatever
     * the queue held next once let a level's coordinator, waiting at its own
     * entity barrier, pick up another level's cells -- or another dimension's
     * whole tick -- and run it nested inside its own pass: its barrier stayed
     * open for the length of somebody else's work, the governor booked that
     * work as its own, and per-thread state filed for one level was read back
     * for another. Liveness does not need the stealing either. Once the
     * caller has claimed the last index, a runner of this batch still sitting
     * in the queue has nothing left to do, so the caller takes it back out and
     * counts it done instead of waiting for a worker to get round to it.
     */
    public void invokeAll(List<Runnable> tasks) {
        invokeAll(tasks.size(), index -> tasks.get(index).run());
    }

    /** Runs indexed work without constructing a list of capturing task lambdas. */
    public void invokeAll(int count, IntConsumer action) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative task count");
        }
        if (count == 0) {
            return;
        }
        if (count == 1) {
            action.accept(0);
            return;
        }

        int runners = Math.min(count, this.workers.length + 1);
        Batch batch = new Batch(count, runners, action, Thread.currentThread());
        // One runner is the caller itself; the rest go to the workers.
        for (int i = 1; i < runners; i++) {
            this.queue.add(batch);
        }
        wake(runners - 1);
        batch.run();

        int spins = 0;
        long opened = System.nanoTime();
        boolean reported = false;
        while (batch.outstanding.get() > 0) {
            if (this.queue.remove(batch)) {
                // A runner no worker picked up. Every index is claimed, so
                // running it would only count it out; do that directly.
                batch.finished();
                spins = 0;
                continue;
            }
            // Every remaining runner is on another thread, so this is the tail
            // of the pass and the only question is how cheaply we can notice
            // it end.
            if (++spins < SPINS) {
                Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(this, 50_000L);
                if (!reported && System.nanoTime() - opened > STALL_NANOS) {
                    reported = true;
                    reportStall(batch.outstanding.get());
                }
            }
        }
        batch.rethrowFailure();
    }

    /**
     * Dumps what every worker is doing. Called once per stalled barrier, from
     * the thread that is stuck waiting on it, so the log names both the waiter
     * and whatever it is waiting for.
     */
    private void reportStall(int outstanding) {
        Skein.LOGGER.error("Skein: a parallel phase has been open for over {}s with {} "
                        + "runner(s) still active. The server is not going to recover on its own; the stacks "
                        + "below are every Skein worker at the moment it stalled.",
                STALL_NANOS / 1_000_000_000L, outstanding);
        // Names, and demotes, whatever the stuck threads are ticking.
        TickContext.reportStuck(this.workers);
        for (TickContext.Worker worker : this.workers) {
            StackTraceElement[] stack = worker.getStackTrace();
            if (stack.length == 0) {
                continue;
            }
            StringBuilder builder = new StringBuilder(worker.getName())
                    .append(" (").append(worker.getState()).append(')');
            for (StackTraceElement frame : stack) {
                builder.append("\n\tat ").append(frame);
            }
            Skein.LOGGER.error(builder.toString());
        }
        Thread self = Thread.currentThread();
        StringBuilder builder = new StringBuilder(self.getName()).append(" (waiting at the barrier)");
        for (StackTraceElement frame : self.getStackTrace()) {
            builder.append("\n\tat ").append(frame);
        }
        Skein.LOGGER.error(builder.toString());
    }

    private void wake(int wanted) {
        for (TickContext.Worker worker : this.workers) {
            if (wanted <= 0) {
                return;
            }
            if (worker.parked) {
                worker.parked = false;
                LockSupport.unpark(worker);
                wanted--;
            }
        }
    }

    public void shutdown() {
        this.running = false;
        // Not cleared: a coordinator still inside invokeAll takes its own
        // unclaimed runners back off the queue, and one that found them gone
        // would wait for runners nobody will ever start.
        for (TickContext.Worker worker : this.workers) {
            worker.parked = false;
            LockSupport.unpark(worker);
            worker.interrupt();
        }
    }
}
