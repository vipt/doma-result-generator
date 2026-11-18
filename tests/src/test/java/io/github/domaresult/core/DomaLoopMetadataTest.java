/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.seasar.doma.internal.expr.ExpressionEvaluator;
import org.seasar.doma.internal.expr.Value;
import org.seasar.doma.internal.jdbc.sql.NodePreparedSqlBuilder;
import org.seasar.doma.internal.jdbc.sql.SqlParser;
import org.seasar.doma.jdbc.Config;
import org.seasar.doma.jdbc.SqlKind;
import org.seasar.doma.jdbc.SqlLogType;
import org.seasar.doma.jdbc.dialect.MssqlDialect;

class DomaLoopMetadataTest {
    // Generic regression for a filter loop and an embedded constant separator; no application classes are available.
    private static final String FILTER = """
            SELECT DISTINCT e.id AS employee_id, e.name AS display_name
            FROM employee e
            WHERE e.id > 0
            /*%if @not.present.Filters@hasItems() */
              AND (
                /*%for item : filter.ids */
                  (e.id = /*item*/1)
                  /*%if item_has_next */ /*# "OR" */ /*%end*/
                /*%end*/
              )
            /*%end*/
            ORDER BY display_name
            """;

    @Test void describesTheOriginalLoopUsingTestValuesAndNeverExecutesTheSelect() throws Exception {
        assertEquals(FILTER, SqlInput.validate(FILTER, SqlInput.Dialect.SQLSERVER));
        try (var real = database()) {
            List<String> sqls = new ArrayList<>();
            AtomicInteger closed = new AtomicInteger();
            var columns = read(guard(real, sqls, closed), FILTER);
            assertEquals(List.of(new ResultColumn("EMPLOYEE_ID", "java.lang.Long"), new ResultColumn("DISPLAY_NAME", "java.lang.String")), columns);
            assertEquals(1, sqls.size());
            assertEquals(1, closed.get());
            assertEquals(2, occurrences(sqls.get(0), "e.id = /*item*/1"));
            assertEquals(1, keywordCount(sqls.get(0), "OR"));
            assertFalse(sqls.get(0).contains("/*%"));
            assertFalse(sqls.get(0).contains("/*#"));
            assertTrue(new EntityGenerator().generate("example.result", "EmployeeResult", columns).contains("private String displayName;"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {
            "/*%if item_has_next*/ /*# \"OR\" */ /*%end*/",
            "/*%if item_has_next == true*/ /*# (\"O\" + \"R\") */ /*%else*/ /*%end*/",
            "/*%if !item_has_next*/ /*%elseif item_index >= 0*/ /*# \"OR\" */ /*%end*/"
    })
    void usesExactlyTheOfficialLoopAndConditionSemantics(String separator) {
        String source = "SELECT id FROM employee WHERE (/*%for item:items*/ id=1 " + separator + " /*%end*/)";
        Config config = new Config() {
            public DataSource getDataSource() { throw new AssertionError("No database access during assembly"); }
            public MssqlDialect getDialect() { return new MssqlDialect(); }
        };
        var evaluator = new ExpressionEvaluator(Map.of("items", new Value(List.class, List.of(0, 0))),
                config.getDialect().getExpressionFunctions(), config.getClassHelper());
        String official = new NodePreparedSqlBuilder(config, SqlKind.SELECT, null, evaluator, SqlLogType.FORMATTED)
                .build(new SqlParser(source).parse(), Function.identity()).getRawSql();
        assertEquals(official, SqlInput.forMetadata(source, SqlInput.Dialect.SQLSERVER));
    }

    @ParameterizedTest @ValueSource(strings = {
            "/*%if item_index > 0*/ /*# \"OR\" */ /*%end*/ id=/*item*/1",
            "id=/*^item*/1 /*%if item_has_next*/ /*# \"OR\" */ /*%end*/"
    })
    void supportsIndexSeparatorsAndLiteralVariableTestValues(String body) throws Exception {
        try (var real = database()) {
            assertEquals(1, read(guard(real, new ArrayList<>(), new AtomicInteger()),
                    "SELECT id FROM employee WHERE (/*%for item:items*/ " + body + " /*%end*/)").size());
        }
    }

    @Test void nestedLoopsAndShadowedIdentifiersRestoreTheOuterFlags() throws Exception {
        String sql = """
                SELECT id FROM employee WHERE (
                /*%for item:outerItems*/
                  (/*%for item:innerItems*/
                    id=/*item*/1 /*%if item_has_next*/ /*# "OR" */ /*%end*/
                  /*%end*/)
                  /*%if item_has_next*/ /*# "AND" */ /*%end*/
                /*%end*/)
                """;
        try (var real = database()) {
            List<String> sqls = new ArrayList<>();
            read(guard(real, sqls, new AtomicInteger()), sql);
            assertEquals(1, sqls.size());
            assertEquals(4, occurrences(sqls.get(0), "id=/*item*/1"));
            assertEquals(2, occurrences(sqls.get(0), "OR"));
            assertEquals(1, occurrences(sqls.get(0), "AND"));
        }
    }

    @Test void loopsThatChangeProjectionSizeAreCheckedWithOneAndTwoSampleElements() throws Exception {
        try (var real = database()) {
            AtomicInteger closed = new AtomicInteger();
            assertThrows(MetadataException.class, () -> read(guard(real, new ArrayList<>(), closed),
                    "SELECT id /*%for item:items*/ /*%if item_index==0*/,1 AS first_extra "
                            + "/*%else*/,1 AS second_extra /*%end*/ /*%end*/ FROM employee"));
            assertEquals(2, closed.get());
        }
    }

    @Test void manyFilterLoopsDoNotMultiplyMetadataVariantCombinations() {
        StringBuilder sql = new StringBuilder("SELECT id FROM employee WHERE 1=1");
        for (int i = 0; i < 8; i++) sql.append(" AND (/*%for item:items").append(i)
                .append("*/ id=1 /*%if item_has_next*/ /*# \"OR\" */ /*%end*/ /*%end*/)");
        assertEquals(1, SqlInput.metadataVariants(sql.toString(), SqlInput.Dialect.SQLSERVER).size());
    }

    @Test void detectsACteInAssembledConstantSqlForTheSqlServerMetadataPreamble() {
        String sql = "/*# \"WITH data_set AS (SELECT 1 AS id)\" */ SELECT id FROM data_set";
        assertTrue(SqlInput.forMetadata(sql, SqlInput.Dialect.SQLSERVER).startsWith(";WITH data_set"));
    }

    @Test void nestedExpansionIsBoundedBeforeDatabaseAccess() {
        StringBuilder sql = new StringBuilder("SELECT id FROM employee WHERE (");
        for (int i = 0; i < 8; i++) sql.append("/*%for item").append(i).append(":items*/ (");
        sql.append("id=1");
        for (int i = 7; i >= 0; i--) sql.append(") /*%if item").append(i).append("_has_next*/ /*# \"OR\" */ /*%end*/ /*%end*/");
        sql.append(")");
        var failure = assertThrows(GenerationException.class, () -> SqlInput.metadataVariants(sql.toString(), SqlInput.Dialect.SQLSERVER));
        assertTrue(failure.getMessage().contains("256"));
    }

    @ParameterizedTest @ValueSource(strings = {
            "SELECT id FROM employee /*# orderBy */",
            "SELECT id FROM employee /*# \"ORDER BY \" + field */",
            "SELECT id FROM employee /*# @java.lang.System@setProperty(\"doma.loop.unexpected\", \"called\") */",
            "SELECT id FROM employee /*# new java.lang.String(\"x\") */",
            "SELECT id FROM employee /*# \"OR\".toLowerCase() */"
    })
    void runtimeEmbeddedExpressionsRemainUnevaluatedAndGiveSpecificGuidance(String sql) {
        var failure = assertThrows(GenerationException.class, () -> SqlInput.validate(sql));
        assertTrue(failure.getMessage().contains("constant expression"));
        assertFalse(failure.getMessage().contains("setProperty"));
        assertNull(System.getProperty("doma.loop.unexpected"));
    }

    @ParameterizedTest @ValueSource(strings = {
            "SELECT 1 /*# \"INTO employee\" */",
            "SELECT 1 /*# \"; DELETE FROM employee\" */",
            "SELECT 1 /*# \"-- hidden\" */",
            "SELECT 1 /*# \"/* hidden */\" */"
    })
    void checksAssembledEmbeddedConstantsBeforeAnyDatabaseOperation(String sql) {
        assertThrows(GenerationException.class, () -> SqlInput.metadataVariants(sql, SqlInput.Dialect.SQLSERVER));
    }

    private static List<ResultColumn> read(Connection connection, String sql) throws Exception {
        return new JdbcMetadataReader().read(connection, sql, "", TypeNameMappings.empty(), stage -> {}, SqlInput.Dialect.SQLSERVER);
    }

    private static Connection database() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MSSQLServer");
        try (var setup = connection.createStatement()) { setup.execute("CREATE TABLE employee (id BIGINT, name VARCHAR(50))"); }
        return connection;
    }

    private static Connection guard(Connection real, List<String> sqls, AtomicInteger closed) {
        return (Connection) Proxy.newProxyInstance(DomaLoopMetadataTest.class.getClassLoader(), new Class<?>[] {Connection.class}, (p, method, args) -> {
            assertEquals("prepareStatement", method.getName());
            String sql = (String) args[0]; sqls.add(sql);
            PreparedStatement statement = real.prepareStatement(sql);
            return Proxy.newProxyInstance(DomaLoopMetadataTest.class.getClassLoader(), new Class<?>[] {PreparedStatement.class}, (s, call, values) -> {
                if (call.getName().startsWith("execute")) fail("Metadata lookup must not execute the SELECT");
                if (call.getName().equals("close")) closed.incrementAndGet();
                try { return call.invoke(statement, values); }
                catch (InvocationTargetException e) { throw e.getCause(); }
            });
        });
    }

    private static int occurrences(String text, String part) { return (text.length() - text.replace(part, "").length()) / part.length(); }
    private static long keywordCount(String text, String word) { return java.util.regex.Pattern.compile("\\b" + word + "\\b").matcher(text).results().count(); }
}
