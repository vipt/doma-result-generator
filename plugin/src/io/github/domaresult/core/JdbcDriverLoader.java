/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

public final class JdbcDriverLoader extends URLClassLoader {
    private boolean closed;
    public JdbcDriverLoader(List<Path> jars) throws IOException {
        super(toUrls(jars), Driver.class.getClassLoader());
    }

    private static URL[] toUrls(List<Path> jars) throws IOException {
        if (jars.isEmpty()) throw new GenerationException("Select at least one JDBC driver JAR.");
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < jars.size(); i++) {
            if (!Files.isRegularFile(jars.get(i))) throw new IOException("Driver JAR does not exist: " + jars.get(i));
            urls[i] = jars.get(i).toUri().toURL();
        }
        return urls;
    }

    public Connection connect(String driverClass, String url, String user, String password) throws Exception {
        Driver driver = Class.forName(driverClass, true, this).asSubclass(Driver.class).getConstructor().newInstance();
        Properties properties = new Properties();
        if (!user.isEmpty()) properties.setProperty("user", user);
        if (!password.isEmpty()) properties.setProperty("password", password);
        Connection connection = driver.connect(url, properties);
        if (connection == null) throw new SQLException("The selected driver does not accept this JDBC URL.");
        return connection;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try (InputStream in = JdbcDriverLoader.class.getResourceAsStream("DriverCleanup.class")) {
            if (in == null) throw new IOException("Missing driver cleanup helper.");
            byte[] bytes = in.readAllBytes();
            Class<?> cleanup = defineClass("io.github.domaresult.core.DriverCleanup", bytes, 0, bytes.length);
            ((Runnable) cleanup.getConstructor().newInstance()).run();
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IOException("Cannot unload JDBC driver.", e);
        } finally {
            super.close();
        }
    }
}
