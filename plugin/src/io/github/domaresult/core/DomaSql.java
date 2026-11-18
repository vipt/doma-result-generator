/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.Function;
import javax.sql.DataSource;
import org.seasar.doma.DomaException;
import org.seasar.doma.internal.expr.ExpressionEvaluator;
import org.seasar.doma.internal.expr.Value;
import org.seasar.doma.internal.jdbc.sql.NodePreparedSqlBuilder;
import org.seasar.doma.internal.jdbc.sql.SqlParser;
import org.seasar.doma.jdbc.*;
import org.seasar.doma.jdbc.dialect.*;

/** Uses unmodified Doma parsers, evaluator, SQL builder and parameter binder. */
final class DomaSql {
    private DomaSql() {}

    static SqlNode parse(String sql) {
        try { return new SqlParser(sql).parse(); }
        catch (DomaException e) { throw failure(e); }
    }

    static DomaPreparedSelect prepare(String sql, Map<String, String> originals, Map<String, Value> arguments,
            ClassLoader loader, SqlInput.Dialect syntax, String jdbcUrl) {
        Dialect dialect = dialect(syntax, jdbcUrl);
        ClassHelper helper = new ClassHelper() {
            @Override @SuppressWarnings("unchecked") public <T> Class<T> forName(String name) throws ClassNotFoundException {
                return (Class<T>) Class.forName(name, true, loader);
            }
        };
        Config config = new Config() {
            @Override public DataSource getDataSource() { throw new UnsupportedOperationException("SQL evaluation does not connect."); }
            @Override public Dialect getDialect() { return dialect; }
            @Override public ClassHelper getClassHelper() { return helper; }
        };
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(loader);
            ExpressionEvaluator evaluator = new ExpressionEvaluator(arguments, dialect.getExpressionFunctions(), helper);
            // No subclass or visitor overrides: IF, FOR, bind, literal and embedded expressions all use Doma.
            PreparedSql prepared = new NodePreparedSqlBuilder(config, SqlKind.SELECT, null, evaluator, SqlLogType.NONE,
                    node -> List.of("*")).build(parse(sql), Function.identity());
            String raw = prepared.getRawSql();
            for (var entry : originals.entrySet()) raw = raw.replace(entry.getKey(), entry.getValue());
            return new DomaPreparedSelect(prepared, config, SqlInput.jdbcSql(raw, syntax));
        } catch (DomaException e) { throw failure(e); }
        catch (GenerationException e) { throw e; }
        catch (Exception | LinkageError e) {
            throw new GenerationException("Cannot evaluate the SQL template with these arguments and project classes. "
                    + "Check argument types, compiled classes and dependencies. SQL and argument values are not logged.");
        } finally { thread.setContextClassLoader(previous); }
    }

    private static GenerationException failure(DomaException e) {
        String missing = "", line = "";
        if (e instanceof JdbcException && e.getArgs() instanceof Object[] values
                && values.length > 1 && values[1] instanceof Integer number) line = " (prepared SQL line " + number + ")";
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof DomaException doma && doma.getMessageResource().getCode().equals("DOMA3003")
                    && doma.getArgs() instanceof Object[] values && values.length > 2 && values[2] instanceof String name
                    && JavaNames.isIdentifier(name)) missing = " Missing argument: " + name + ".";
        }
        return new GenerationException("Doma template processing failed: " + e.getMessageResource().getCode()
                + line + "." + missing + " Check Arguments, directive syntax and the project's compiled classes. SQL and argument values are not logged.");
    }

    private static Dialect dialect(SqlInput.Dialect syntax, String url) {
        if (syntax == SqlInput.Dialect.SQLSERVER) return new MssqlDialect();
        if (syntax == SqlInput.Dialect.POSTGRESQL || syntax == SqlInput.Dialect.POSTGRESQL_LEGACY) return new PostgresDialect();
        if (syntax == SqlInput.Dialect.MYSQL || syntax == SqlInput.Dialect.MYSQL_NO_BACKSLASH) return new MysqlDialect();
        String value = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (value.startsWith("jdbc:oracle:")) return new OracleDialect();
        if (value.startsWith("jdbc:h2:")) return new H2Dialect();
        if (value.startsWith("jdbc:db2:")) return new Db2Dialect();
        if (value.startsWith("jdbc:hsqldb:")) return new HsqldbDialect();
        if (value.startsWith("jdbc:sqlite:")) return new SqliteDialect();
        return new StandardDialect();
    }
}
