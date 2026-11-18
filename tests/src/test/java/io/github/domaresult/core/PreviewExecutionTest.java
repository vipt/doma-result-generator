/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class PreviewExecutionTest {
    private static final Duration LIMIT = Duration.ofSeconds(5);

    @Test void returnsResultsOnDaemonWorkersAndPreservesTheActualFailure() throws Exception {
        try (var execution = new PreviewExecution(1)) {
            for (int i = 0; i < 25; i++) assertEquals(Boolean.TRUE,
                    execution.run(token -> Thread.currentThread().isDaemon(), () -> false, LIMIT));
            var failure = new java.sql.SQLException("test failure", "72000", 1017);
            var thrown = assertThrows(ExecutionException.class, () -> execution.run(token -> { throw failure; }, () -> false, LIMIT));
            assertSame(failure, thrown.getCause());
        }
    }

    @Test void alreadyCanceledPreviewDoesNotStartAConnectionAttempt() {
        try (var execution = new PreviewExecution(1)) {
            AtomicBoolean started = new AtomicBoolean();
            assertThrows(CancellationException.class, () -> execution.run(token -> { started.set(true); return "late"; }, () -> true, LIMIT));
            assertFalse(started.get());
        }
    }

    @Test void cancelReturnsWithoutWaitingForAnUninterruptibleConnectAndClosesALateConnection() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), closed = new CountDownLatch(1);
        AtomicBoolean canceled = new AtomicBoolean();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (var execution = new PreviewExecution(1)) {
            Future<String> result = caller.submit(() -> execution.run(token -> {
                started.countDown();
                awaitIgnoringInterrupts(release);
                Connection connection = proxy(Connection.class, (p, method, args) -> {
                    if (method.getName().equals("close")) closed.countDown();
                    return null;
                });
                try (connection) { token.track(connection); return "must not publish"; }
                finally { token.release(connection); }
            }, canceled::get, LIMIT));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            canceled.set(true);
            assertInstanceOf(CancellationException.class, assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS)).getCause());
            assertEquals(1, closed.getCount());
            release.countDown();
            assertTrue(closed.await(2, TimeUnit.SECONDS));
        } finally { release.countDown(); caller.shutdownNow(); }
    }

    @Test void deadlineReturnsWhileADriverIgnoresInterruptionAndBoundsRetries() throws Exception {
        CountDownLatch release = new CountDownLatch(1), finished = new CountDownLatch(1);
        try (var execution = new PreviewExecution(1)) {
            try {
                assertThrows(TimeoutException.class, () -> execution.run(token -> {
                    try { awaitIgnoringInterrupts(release); return "too late"; }
                    finally { finished.countDown(); }
                }, () -> false, Duration.ofMillis(500)));
                assertEquals(1, finished.getCount());
                assertThrows(GenerationException.class, () -> execution.run(token -> "retry", () -> false, LIMIT));
            } finally { release.countDown(); }
            assertTrue(finished.await(2, TimeUnit.SECONDS));
        }
    }

    @Test void cancellationWinsOverACompletedResult() {
        try (var execution = new PreviewExecution(1)) {
            AtomicBoolean canceled = new AtomicBoolean();
            assertThrows(CancellationException.class, () -> execution.run(token -> { canceled.set(true); return "discard"; }, canceled::get, LIMIT));
        }
    }

    @Test void abortRunsIndependentlyOfABlockedOfficialSelectAndStatementCancel() throws Exception {
        CountDownLatch reading = new CountDownLatch(1), releaseRead = new CountDownLatch(1), releaseCancel = new CountDownLatch(1);
        CountDownLatch cancelStarted = new CountDownLatch(1), aborted = new CountDownLatch(1);
        AtomicBoolean canceled = new AtomicBoolean(), correctLoader = new AtomicBoolean();
        ClassLoader driverLoader = new ClassLoader() {};
        PreparedStatement statement = proxy(PreparedStatement.class, (p, method, args) -> {
            if (method.getName().equals("executeQuery")) { reading.countDown(); awaitIgnoringInterrupts(releaseRead); throw new CancellationException(); }
            if (method.getName().equals("cancel")) { cancelStarted.countDown(); awaitIgnoringInterrupts(releaseCancel); return null; }
            if (method.getName().equals("close")) return null;
            throw new UnsupportedOperationException(method.getName());
        });
        Connection connection = proxy(Connection.class, (p, method, args) -> {
            if (method.getName().equals("prepareStatement")) return statement;
            if (method.getName().equals("abort")) {
                ((Executor) args[0]).execute(() -> {
                    correctLoader.set(Thread.currentThread().getContextClassLoader() == driverLoader);
                    aborted.countDown(); releaseRead.countDown();
                });
                return null;
            }
            if (method.getName().equals("close")) return null;
            throw new UnsupportedOperationException(method.getName());
        });
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (var execution = new PreviewExecution(1)) {
            Future<?> result = caller.submit(() -> execution.run(token -> {
                token.driverLoader(driverLoader);
                try (connection) {
                    token.track(connection);
                    return new JdbcMetadataReader().read(connection, "SELECT 1", "", TypeNameMappings.empty(), stage -> {}, SqlInput.Dialect.STANDARD, token);
                } finally { token.release(connection); }
            }, canceled::get, LIMIT));
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            canceled.set(true);
            assertInstanceOf(CancellationException.class, assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS)).getCause());
            assertTrue(cancelStarted.await(2, TimeUnit.SECONDS));
            assertTrue(aborted.await(2, TimeUnit.SECONDS));
            assertTrue(correctLoader.get());
        } finally { releaseRead.countDown(); releaseCancel.countDown(); caller.shutdownNow(); }
    }

    @Test void unsupportedAbortFallsBackToClosingTheConnectionOffTheWaitingThread() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), closed = new CountDownLatch(1);
        AtomicBoolean canceled = new AtomicBoolean();
        Connection connection = proxy(Connection.class, (p, method, args) -> {
            if (method.getName().equals("abort")) throw new SQLFeatureNotSupportedException();
            if (method.getName().equals("close")) { closed.countDown(); release.countDown(); return null; }
            throw new UnsupportedOperationException(method.getName());
        });
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (var execution = new PreviewExecution(1)) {
            Future<?> result = caller.submit(() -> execution.run(token -> {
                try (connection) { token.track(connection); started.countDown(); awaitIgnoringInterrupts(release); token.check(); return "discard"; }
                finally { token.release(connection); }
            }, canceled::get, LIMIT));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            canceled.set(true);
            assertInstanceOf(CancellationException.class, assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS)).getCause());
            assertTrue(closed.await(2, TimeUnit.SECONDS));
        } finally { release.countDown(); caller.shutdownNow(); }
    }

    @Test void oracleTimeoutsArePerConnectionAndOtherDriversKeepTheirProperties() throws Exception {
        int global = DriverManager.getLoginTimeout();
        var properties = JdbcDriverLoader.connectionProperties("oracle.jdbc.OracleDriver", "test", " p@ss word ", 7);
        assertEquals("7000", properties.getProperty("oracle.net.CONNECT_TIMEOUT"));
        assertEquals("7000", properties.getProperty("oracle.jdbc.ReadTimeout"));
        assertEquals(" p@ss word ", properties.getProperty("password"));
        assertEquals(2, JdbcDriverLoader.connectionProperties("org.h2.Driver", "test", "password", 7).size());
        assertEquals(global, DriverManager.getLoginTimeout());
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try { latch.await(); break; }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(PreviewExecutionTest.class.getClassLoader(), new Class<?>[] { type }, handler));
    }
}
