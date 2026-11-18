/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.seasar.doma.extension.gen.GenException;
import org.seasar.doma.extension.gen.ResultSetMetaReader;
import org.seasar.doma.extension.gen.dialect.GenDialect;
import org.seasar.doma.extension.gen.dialect.GenDialectRegistry;
import org.seasar.doma.extension.gen.dialect.StandardGenDialect;

/** Doma builds the SELECT and parameters; doma-gen reads its result metadata. */
public final class JdbcMetadataReader {
    public List<ResultColumn> read(Connection connection, String sql) throws SQLException {
        return read(connection, sql, "", TypeNameMappings.empty());
    }
    public List<ResultColumn> read(Connection connection, String sql, String className, TypeNameMappings mappings) throws SQLException {
        return read(connection, sql, className, mappings, stage -> {});
    }
    public List<ResultColumn> read(Connection connection, String sql, String className, TypeNameMappings mappings,
            Consumer<JdbcErrorDetails.Stage> progress) throws SQLException {
        return read(connection, sql, className, mappings, progress, SqlInput.Dialect.STANDARD);
    }
    public List<ResultColumn> read(Connection connection, String sql, String className, TypeNameMappings mappings,
            Consumer<JdbcErrorDetails.Stage> progress, SqlInput.Dialect syntax) throws SQLException {
        return read(connection, sql, className, mappings, progress, syntax, new PreviewExecution.Cancellation());
    }
    public List<ResultColumn> read(Connection connection, String sql, String className, TypeNameMappings mappings,
            Consumer<JdbcErrorDetails.Stage> progress, SqlInput.Dialect syntax,
            PreviewExecution.Cancellation cancellation) throws SQLException {
        return read(connection, sql, className, mappings, progress, syntax, cancellation, "");
    }
    public List<ResultColumn> read(Connection connection, String sql, String className, TypeNameMappings mappings,
            Consumer<JdbcErrorDetails.Stage> progress, SqlInput.Dialect syntax,
            PreviewExecution.Cancellation cancellation, String jdbcUrl) throws SQLException {
        return read(connection, sql, className, mappings, progress, syntax, cancellation, jdbcUrl,
                JdbcMetadataReader.class.getClassLoader(), Map.of());
    }
    public List<ResultColumn> read(Connection connection, String sql, String className, TypeNameMappings mappings,
            Consumer<JdbcErrorDetails.Stage> progress, SqlInput.Dialect syntax,
            PreviewExecution.Cancellation cancellation, String jdbcUrl, ClassLoader projectClasses,
            Map<String, Type> parameterTypes) throws SQLException {
        cancellation.check();
        progress.accept(JdbcErrorDetails.Stage.PROCESSING_TEMPLATE);
        var prepared = DomaSql.prepare(sql, syntax, jdbcUrl, projectClasses, parameterTypes, cancellation);
        return read(connection, prepared, className, mappings, progress, syntax, cancellation, jdbcUrl);
    }
    public List<ResultColumn> read(Connection connection, DomaPreparedSelect prepared, String className, TypeNameMappings mappings,
            Consumer<JdbcErrorDetails.Stage> progress, SqlInput.Dialect syntax,
            PreviewExecution.Cancellation cancellation, String jdbcUrl) throws SQLException {
        cancellation.check();
        String query = prepared.sql();
        GenDialect dialect = dialect(syntax, jdbcUrl);
        // The official reader owns this borrowed handle; the dialog owns the real connection.
        Connection borrowed = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p, method, args) -> {
            if (method.getName().equals("close")) return null;
            if (method.getName().equals("prepareStatement")) {
                cancellation.check(); progress.accept(JdbcErrorDetails.Stage.PREPARING_SQL);
                PreparedStatement statement = (PreparedStatement) invoke(method, connection, args);
                try {
                    cancellation.track(statement);
                    prepared.bind(statement);
                }
                catch (SQLException | RuntimeException | Error failure) {
                    try { statement.close(); } catch (SQLException close) { failure.addSuppressed(close); }
                    finally { cancellation.release(statement); }
                    throw failure;
                }
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (s, call, values) -> {
                    if (call.getName().equals("executeQuery")) {
                        cancellation.check(); progress.accept(JdbcErrorDetails.Stage.EXECUTING_SELECT);
                        ResultSet result = (ResultSet) invoke(call, statement, values);
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class}, (r, operation, parameters) -> {
                            if (operation.getName().equals("close")) return invoke(operation, result, parameters);
                            cancellation.check();
                            if (operation.getName().equals("getMetaData")) progress.accept(JdbcErrorDetails.Stage.READING_COLUMNS);
                            return invoke(operation, result, parameters);
                        });
                    }
                    if (call.getName().equals("close")) {
                        try { return invoke(call, statement, values); }
                        finally { cancellation.release(statement); }
                    }
                    return invoke(call, statement, values);
                });
            }
            return invoke(method, connection, args);
        });
        DataSource source = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class}, (p, method, args) -> {
            if (method.getName().equals("getConnection") && (args == null || args.length == 0)) return borrowed;
            throw new SQLFeatureNotSupportedException("This data source exposes only the dialog connection.");
        });
        try {
            var table = new ResultSetMetaReader(dialect, source).read(query);
            cancellation.check();
            var metadata = table.getColumnMetas();
            List<String> fields = JavaNames.fieldNames(metadata.stream().map(column -> column.getName()).toList());
            List<ResultColumn> result = new ArrayList<>();
            for (int i = 0; i < metadata.size(); i++) {
                cancellation.check();
                var column = metadata.get(i);
                var override = mappings.resolve(className, fields.get(i));
                String type = override.isPresent() ? override.get() : dialect.getMappedPropertyClassName(column);
                if (type == null) throw new MetadataException("Unsupported JDBC type " + column.getSqlType()
                        + " for column '" + column.getName() + "'. Use name.properties or an explicit CAST.");
                result.add(new ResultColumn(column.getName(), type));
            }
            return List.copyOf(result);
        } catch (GenException e) {
            cancellation.check();
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException failure) throw failure;
            }
            throw new GenerationException("Official doma-gen could not read the SELECT result. Check Metadata SQL, project classes and supported column types.");
        }
    }
    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
    private static GenDialect dialect(SqlInput.Dialect syntax, String url) {
        String name = switch (syntax) {
            case SQLSERVER -> "mssql"; case POSTGRESQL, POSTGRESQL_LEGACY -> "postgres";
            case MYSQL, MYSQL_NO_BACKSLASH -> "mysql"; default -> "standard";
        };
        String value = url.toLowerCase(Locale.ROOT);
        for (String candidate : List.of("oracle", "h2", "hsqldb", "db2")) if (value.startsWith("jdbc:" + candidate + ":")) name = candidate;
        return name.equals("standard") ? new StandardGenDialect() : GenDialectRegistry.lookup(name);
    }
}
