/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import javax.sql.DataSource;
import org.seasar.doma.DomaException;
import org.seasar.doma.internal.expr.ExpressionEvaluator;
import org.seasar.doma.internal.jdbc.sql.NodePreparedSqlBuilder;
import org.seasar.doma.internal.jdbc.sql.SqlParser;
import org.seasar.doma.jdbc.*;
import org.seasar.doma.jdbc.dialect.*;

/** Calls Doma's unmodified parser, evaluator, SQL builder and typed parameter binder. */
public final class DomaSql {
    private DomaSql() {}

    public static DomaPreparedSelect prepare(String sql, SqlInput.Dialect syntax, String jdbcUrl,
            ClassLoader loader, Map<String, Type> parameterTypes, PreviewExecution.Cancellation cancellation) {
        return prepare(sql, syntax, jdbcUrl, loader, names -> parameterTypes, cancellation);
    }
    public static DomaPreparedSelect prepare(String sql, SqlInput.Dialect syntax, String jdbcUrl,
            ClassLoader loader, Function<Set<String>, Map<String, Type>> parameterTypes, PreviewExecution.Cancellation cancellation) {
        cancellation.check();
        String checked = SqlInput.validate(sql, syntax);
        Dialect dialect = dialect(syntax, jdbcUrl);
        ClassHelper helper = new ClassHelper() {
            @Override @SuppressWarnings("unchecked") public <T> Class<T> forName(String name) throws ClassNotFoundException {
                return (Class<T>) Class.forName(name, true, loader);
            }
        };
        Config config = new Config() {
            @Override public DataSource getDataSource() { throw new UnsupportedOperationException("Template processing does not connect."); }
            @Override public Dialect getDialect() { return dialect; }
            @Override public ClassHelper getClassHelper() { return helper; }
        };
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(loader);
            SqlNode tree = new SqlParser(checked).parse();
            var arguments = AutomaticArguments.create(tree, parameterTypes, cancellation);
            var evaluator = new ExpressionEvaluator(arguments, dialect.getExpressionFunctions(), helper);
            // No subclass, visitor overrides, branch selection, retries or SQL replacements.
            // The expansion hook supplies '*' for JDBC column discovery; Doma handles the alias.
            PreparedSql prepared = new NodePreparedSqlBuilder(config, SqlKind.SELECT, null, evaluator, SqlLogType.NONE,
                    node -> List.of("*")).build(tree, Function.identity());
            cancellation.check();
            String query = SqlInput.validateJdbc(prepared.getRawSql(), syntax);
            return new DomaPreparedSelect(prepared, config, query);
        } catch (DomaException e) { throw failure(e); }
        catch (GenerationException e) { throw e; }
        catch (java.util.concurrent.CancellationException e) { throw e; }
        catch (RuntimeException | LinkageError e) {
            throw new GenerationException("Doma could not process the template with automatic arguments. "
                    + "Build the project and check its DAO parameter constructors, methods and dependencies. SQL and argument values are not logged.");
        } finally { thread.setContextClassLoader(previous); }
    }

    private static GenerationException failure(DomaException e) {
        String line = "";
        if (e instanceof JdbcException && e.getArgs() instanceof Object[] values
                && values.length > 1 && values[1] instanceof Integer number) line = " (SQL line " + number + ")";
        return new GenerationException("Doma template processing failed: " + e.getMessageResource().getCode() + line
                + ". Build the project and check directive syntax, DAO parameter types and dependencies. Automatic arguments require no manual input. SQL and argument values are not logged.");
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
