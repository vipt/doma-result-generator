/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class SqlServerCteMetadataTest {
    private static final String CTE = "WITH sample AS (SELECT CAST(1 AS BIGINT) AS id) SELECT id FROM sample";

    @ParameterizedTest @ValueSource(strings = { "", " \n", "-- header\n", "/** SELECT inside a header */\n", "/** outer /* nested */ comment */\n" })
    void terminatesTheDriverMetadataPreambleBeforeCtesIncludingCommentedHeaders(String header) {
        String source = header + CTE;
        assertEquals(source, SqlInput.validate(source, SqlInput.Dialect.SQLSERVER));
        String metadata = SqlInput.forMetadata(source, SqlInput.Dialect.SQLSERVER);
        assertTrue(metadata.startsWith(";"));
        assertTrue(metadata.endsWith(CTE));
        if (!header.isBlank()) assertTrue(metadata.contains(header.strip()));
        assertEquals(metadata, SqlInput.forMetadata(metadata, SqlInput.Dialect.SQLSERVER));
    }

    @ParameterizedTest @ValueSource(strings = { ";", ";;", " ; -- header\n", "/** header */ ; /** after separator */\n" })
    void acceptsExistingCteSeparatorsWithoutAddingAnother(String prefix) {
        String source = prefix + CTE;
        assertEquals(source, SqlInput.validate(source, SqlInput.Dialect.SQLSERVER));
        String metadata = SqlInput.forMetadata(source, SqlInput.Dialect.SQLSERVER);
        assertTrue(metadata.startsWith(";"));
        assertFalse(metadata.startsWith(";;"));
        assertTrue(metadata.endsWith(CTE));
    }

    @ParameterizedTest @EnumSource(value = SqlInput.Dialect.class, names = "SQLSERVER", mode = EnumSource.Mode.EXCLUDE)
    void otherDatabaseSyntaxKeepsItsOriginalCteAndDoesNotAcceptLeadingBatchSeparators(SqlInput.Dialect dialect) {
        assertEquals(CTE, SqlInput.forMetadata(CTE, dialect));
        assertThrows(GenerationException.class, () -> SqlInput.validate(";" + CTE, dialect));
    }

    @ParameterizedTest @ValueSource(strings = {
            "SELECT 1 AS id",
            "-- WITH is just a comment\nSELECT 1 AS id",
            "SELECT 'WITH example' AS value",
            "SELECT [WITH] FROM example",
            "SELECT id FROM example WITH (NOLOCK)"
    })
    void ordinarySelectsAndTableHintsAreNotTreatedAsLeadingCtes(String sql) {
        assertEquals(sql, SqlInput.forMetadata(sql, SqlInput.Dialect.SQLSERVER));
    }

    @ParameterizedTest @ValueSource(strings = {
            ";SELECT 1",
            "; /** header */ DELETE FROM example",
            ";WITH sample AS (SELECT 1 AS id) DELETE FROM sample",
            ";WITH sample AS (UPDATE example SET id=1) SELECT id FROM sample",
            ";WITH sample AS (SELECT 1 AS id); SELECT id FROM sample",
            ";;WITH sample AS (SELECT 1 AS id) SELECT id FROM sample; SELECT 2",
            "SELECT 1; WITH sample AS (SELECT 1 AS id) SELECT id FROM sample",
            "/*%expand*/*; WITH sample AS (SELECT 1 AS id) SELECT id FROM sample",
            "; -- empty batch\n"
    })
    void separatorSupportStillRejectsWritesAndAdditionalStatements(String sql) {
        assertThrows(GenerationException.class, () -> SqlInput.forMetadata(sql, SqlInput.Dialect.SQLSERVER));
    }

    @Test void retainsConditionalFiltersAndTheVisibleSqlWhileNormalizingOnlyJdbcSyntax() {
        String source = "with sample as (select 1 as id) select id from sample where 1=1 /*%if true*/ and id>0 /*%end*/; -- end";
        String visible = source.replace("; -- end", " -- end");
        assertEquals(visible, SqlInput.validate(source, SqlInput.Dialect.SQLSERVER));
        String metadata = SqlInput.forMetadata(source, SqlInput.Dialect.SQLSERVER);
        assertTrue(metadata.startsWith(";with sample"));
        assertFalse(metadata.contains("/*%if"));
        assertTrue(metadata.contains("and id>0"));
    }

    @Test void readsCteMetadataWithoutCallingExecuteOrChangingTheCallersSource() throws Exception {
        try (var real = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MSSQLServer")) {
            String source = """
                    WITH prices AS (SELECT CAST(1 AS BIGINT) AS id, CAST(12.50 AS DECIMAL(10,2)) AS amount),
                         filtered AS (SELECT id, amount FROM prices WHERE 1=1 /*%if true*/ AND id>0 /*%end*/)
                    SELECT id AS item_id, amount FROM filtered
                    """;
            AtomicReference<String> preparedSql = new AtomicReference<>();
            Connection guarded = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class }, (proxy, method, args) -> {
                assertEquals("prepareStatement", method.getName());
                String sql = (String) args[0];
                preparedSql.set(sql);
                // H2 treats a leading empty batch statement differently; consume that separator in this
                // SQL Server adapter model before delegating the SELECT's type description to H2.
                PreparedStatement statement = real.prepareStatement(sql.startsWith(";") ? sql.substring(1) : sql);
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { PreparedStatement.class }, (p, call, values) -> {
                    if (call.getName().startsWith("execute")) fail("The metadata reader must not execute the SELECT");
                    // Microsoft JDBC's getMetaData prefixes user SQL with SET FMTONLY ON (no semicolon).
                    // Model that documented preamble requirement, then use real H2 metadata for column types.
                    if (call.getName().equals("getMetaData") && !("SET FMTONLY ON " + sql).startsWith("SET FMTONLY ON ;")) {
                        throw new SQLException("private SQL syntax details", "S0001", 319);
                    }
                    try { return call.invoke(statement, values); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
            });
            try (var unsupported = guarded.prepareStatement(source)) {
                SQLException failure = assertThrows(SQLException.class, unsupported::getMetaData);
                assertEquals(319, failure.getErrorCode());
            }
            assertEquals(List.of(new ResultColumn("ITEM_ID", "java.lang.Long"), new ResultColumn("AMOUNT", "java.math.BigDecimal")),
                    new JdbcMetadataReader().read(guarded, source, "", TypeNameMappings.empty(), stage -> {}, SqlInput.Dialect.SQLSERVER));
            assertTrue(preparedSql.get().startsWith(";WITH prices"));
            assertFalse(preparedSql.get().contains("/*%if"));
            assertEquals(source, SqlInput.validate(source, SqlInput.Dialect.SQLSERVER));
        }
    }
}
