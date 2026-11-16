/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Bounds UI waiting independently of whether a JDBC driver responds to interruption. */
public final class PreviewExecution implements AutoCloseable {
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final int MAX_TIMEOUT_SECONDS = 600;
    public static final PreviewExecution DEFAULT = new PreviewExecution(2);
    private static final ThreadPoolExecutor CLEANUP = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), daemonThreads("Doma JDBC cleanup"));
    static { CLEANUP.allowCoreThreadTimeOut(true); }
    private final ThreadPoolExecutor workers;
    private final Semaphore slots;

    @FunctionalInterface public interface Work<T> { T run(Cancellation cancellation) throws Exception; }

    PreviewExecution(int concurrency) {
        slots = new Semaphore(concurrency);
        workers = new ThreadPoolExecutor(concurrency, concurrency, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(concurrency), daemonThreads("Doma JDBC preview"));
        workers.allowCoreThreadTimeOut(true);
    }

    private static ThreadFactory daemonThreads(String name) {
        return action -> {
            Thread thread = new Thread(action, name);
            thread.setDaemon(true);
            thread.setContextClassLoader(PreviewExecution.class.getClassLoader());
            return thread;
        };
    }

    public <T> T run(Work<T> work, BooleanSupplier canceled, Duration timeout)
            throws ExecutionException, InterruptedException, TimeoutException {
        long limit = timeout.toNanos();
        if (limit <= 0) throw new IllegalArgumentException("Preview timeout must be positive.");
        if (canceled.getAsBoolean()) throw new CancellationException();
        Cancellation cancellation = new Cancellation();
        long started = System.nanoTime();
        if (!slots.tryAcquire()) throw busy();
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> { if (released.compareAndSet(false, true)) slots.release(); };
        FutureTask<T> future = new FutureTask<>(() -> {
            try { return work.run(cancellation); }
            finally { release.run(); }
        });
        try { workers.execute(() -> { try { future.run(); } finally { release.run(); } }); }
        catch (RejectedExecutionException e) {
            release.run();
            throw busy();
        }
        boolean success = false;
        try {
            while (true) {
                if (canceled.getAsBoolean()) throw new CancellationException();
                long remaining = limit - (System.nanoTime() - started);
                if (remaining <= 0) throw new TimeoutException();
                T result;
                try { result = future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS); }
                catch (TimeoutException tick) { continue; }
                if (canceled.getAsBoolean()) throw new CancellationException();
                if (System.nanoTime() - started >= limit) throw new TimeoutException();
                success = true;
                return result;
            }
        } finally {
            if (!success) {
                cancellation.cancel();
                future.cancel(true);
            }
        }
    }

    private static GenerationException busy() {
        return new GenerationException("Previous JDBC previews are still stopping. Retry after the driver releases them, or check its connection timeouts.");
    }

    @Override public void close() { workers.shutdownNow(); }

    public static final class Cancellation {
        private final AtomicBoolean canceled = new AtomicBoolean();
        private final AtomicReference<Connection> connection = new AtomicReference<>();
        private final AtomicReference<Statement> statement = new AtomicReference<>();
        private volatile ClassLoader driverLoader;

        public void check() {
            if (isCanceled()) throw new CancellationException();
        }

        public boolean isCanceled() { return canceled.get() || Thread.currentThread().isInterrupted(); }

        public void driverLoader(ClassLoader loader) { driverLoader = loader; }

        public void track(Connection value) {
            connection.set(value);
            if (canceled.get()) abort(connection.getAndSet(null));
            check();
        }
        public void release(Connection value) { connection.compareAndSet(value, null); }

        public void track(Statement value) {
            statement.set(value);
            if (canceled.get()) cancelStatement(statement.getAndSet(null));
            check();
        }
        public void release(Statement value) { statement.compareAndSet(value, null); }

        private void cancel() {
            if (!canceled.compareAndSet(false, true)) return;
            cancelStatement(statement.getAndSet(null));
            abort(connection.getAndSet(null));
        }

        private void cancelStatement(Statement value) {
            if (value != null) cleanup(() -> { try { value.cancel(); } catch (Exception | LinkageError ignored) {} });
        }

        private void abort(Connection value) {
            if (value == null) return;
            cleanup(() -> {
                try { value.abort(Runnable::run); }
                catch (Exception | LinkageError unsupported) {
                    try { value.close(); } catch (Exception | LinkageError ignored) {}
                }
            });
        }

        private void cleanup(Runnable action) {
            ClassLoader loader = driverLoader;
            try {
                CLEANUP.execute(() -> {
                    Thread thread = Thread.currentThread();
                    ClassLoader previous = thread.getContextClassLoader();
                    try {
                        if (loader != null) thread.setContextClassLoader(loader);
                        action.run();
                    } finally { thread.setContextClassLoader(previous); }
                });
            } catch (RejectedExecutionException busy) {
                // Resource ownership stays with the JDBC worker's try/finally blocks.
            }
        }
    }
}
