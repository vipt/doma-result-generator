/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.sql.DriverManager;
import java.sql.SQLException;

/** Loaded in the driver class loader so JDBC permits deregistering its drivers. */
public final class DriverCleanup implements Runnable {
    @Override
    public void run() {
        DriverManager.getDrivers().asIterator().forEachRemaining(driver -> {
            if (driver.getClass().getClassLoader() == getClass().getClassLoader()) {
                try { DriverManager.deregisterDriver(driver); }
                catch (SQLException e) { throw new IllegalStateException("Cannot unload JDBC driver.", e); }
            }
        });
    }
}
