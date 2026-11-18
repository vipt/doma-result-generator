/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.DriverManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlInputDialectTest {
    @Test void nonNestedCommentsAllowPathsAndDoNotHideFollowingBindOrWriteTokens() {
        String sql = "/** see META-INF/*.sql */ SELECT id FROM employee WHERE id = /* id */1";
        assertEquals(sql, SqlInput.validate(sql));
        assertEquals(sql, SqlInput.validate(sql, SqlInput.Dialect.MYSQL));
        assertThrows(GenerationException.class, () -> SqlInput.validate("/** see META-INF/*.sql */ DELETE FROM employee; SELECT 1"));
        assertThrows(GenerationException.class, () -> SqlInput.validate(sql, SqlInput.Dialect.POSTGRESQL));
        String extraClose = "SELECT 1 AS a /** /* */ ; DELETE FROM t -- */";
        assertThrows(GenerationException.class, () -> SqlInput.validate(extraClose));
        assertEquals(extraClose, SqlInput.validate(extraClose, SqlInput.Dialect.POSTGRESQL));
        assertEquals(extraClose, SqlInput.validate(extraClose, SqlInput.Dialect.SQLSERVER));
    }

    @Test void nestedCommentsAreSupportedForPostgresqlAndSqlServer() {
        String sql = "/** outer /* inner */ end */ SELECT 1";
        assertEquals(sql, SqlInput.validate(sql, SqlInput.Dialect.POSTGRESQL));
        assertEquals(sql, SqlInput.validate(sql, SqlInput.Dialect.SQLSERVER));
        assertThrows(GenerationException.class, () -> SqlInput.validate(sql));
        String writeInside = "/** outer /* inner */ DELETE FROM employee */ SELECT 1";
        assertEquals(writeInside, SqlInput.validate(writeInside, SqlInput.Dialect.POSTGRESQL));
        assertThrows(GenerationException.class, () -> SqlInput.validate(writeInside, SqlInput.Dialect.MYSQL));
    }

    @ParameterizedTest @ValueSource(strings = { "FOR UPDATE", "FOR UPDATE NOWAIT", "FOR UPDATE OF e.id", "FOR UPDATE SKIP LOCKED", "FOR NO KEY UPDATE", "FOR /** lock */ UPDATE" })
    void acceptsLockingSelectsWithoutRewritingTheirMeaning(String locking) {
        String sql = "SELECT e.id FROM employee e WHERE e.id = /* id */1 " + locking;
        assertEquals(sql, SqlInput.validate(sql));
    }

    @ParameterizedTest @ValueSource(strings = { "UPDATE employee SET id = 1", "SELECT 1 FOR UPDATE; DELETE FROM employee", "WITH c AS (UPDATE employee SET id=1 RETURNING id) SELECT id FROM c", "SELECT 1 FOR UPDATE UPDATE employee SET id=1" })
    void lockingSupportStillRejectsWritesAndMultipleStatements(String sql) {
        assertThrows(GenerationException.class, () -> SqlInput.validate(sql));
    }

    @Test void mysqlAndPostgresqlEscapesRespectTheSelectedSyntaxMode() {
        String mysql = "SELECT 'it\\'s; DELETE FROM employee' AS label";
        assertEquals(mysql, SqlInput.validate(mysql, SqlInput.Dialect.MYSQL));
        assertThrows(GenerationException.class, () -> SqlInput.validate(mysql));
        assertThrows(GenerationException.class, () -> SqlInput.validate(mysql, SqlInput.Dialect.MYSQL_NO_BACKSLASH));
        String postgres = "SELECT E'it\\'s' AS label";
        assertEquals(postgres, SqlInput.validate(postgres, SqlInput.Dialect.POSTGRESQL));
        assertThrows(GenerationException.class, () -> SqlInput.validate(postgres));
        String standard = "SELECT '\\' AS slash";
        assertEquals(standard, SqlInput.validate(standard, SqlInput.Dialect.POSTGRESQL));
        assertEquals(standard, SqlInput.validate(standard, SqlInput.Dialect.MYSQL_NO_BACKSLASH));
        assertThrows(GenerationException.class, () -> SqlInput.validate(standard, SqlInput.Dialect.POSTGRESQL_LEGACY));
    }

    @Test void escapedBackslashesDoNotHideARealStatementTerminator() {
        String sql = "SELECT '\\\\'; DELETE FROM employee";
        assertThrows(GenerationException.class, () -> SqlInput.validate(sql, SqlInput.Dialect.MYSQL));
        assertThrows(GenerationException.class, () -> SqlInput.validate("SELECT E'\\\\'; DELETE FROM employee", SqlInput.Dialect.POSTGRESQL));
    }

    @Test void selectsSyntaxFromJdbcUrlsWithoutConnecting() {
        assertEquals(SqlInput.Dialect.POSTGRESQL, SqlInput.Dialect.fromJdbcUrl("jdbc:postgresql://localhost/db"));
        assertEquals(SqlInput.Dialect.MYSQL, SqlInput.Dialect.fromJdbcUrl("jdbc:mariadb://localhost/db"));
        assertEquals(SqlInput.Dialect.MYSQL, SqlInput.Dialect.fromJdbcUrl("JDBC:MYSQL://localhost/db"));
        assertEquals(SqlInput.Dialect.SQLSERVER, SqlInput.Dialect.fromJdbcUrl("jdbc:jtds:sqlserver://localhost/db"));
        assertEquals(SqlInput.Dialect.STANDARD, SqlInput.Dialect.fromJdbcUrl("jdbc:oracle:thin:@localhost/service"));
    }

    @Test void describesForUpdateWithTheOfficialReader() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID())) {
            assertEquals(List.of(new ResultColumn("ID", "java.lang.Integer")),
                    new JdbcMetadataReader().read(connection, "SELECT 1 AS id FOR UPDATE"));
        }
    }

    @Test void officialReaderPreservesTheChosenSqlDialect() throws Exception {
        String sql = "SELECT 'it''s' AS label";
        ResultSetMetaData metadata = (ResultSetMetaData) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { ResultSetMetaData.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnCount" -> 1;
                    case "getColumnLabel" -> "LABEL";
                    case "getColumnType" -> Types.VARCHAR;
                    case "getColumnTypeName" -> "VARCHAR";
                    case "getTableName" -> "";
                    case "getPrecision", "getScale" -> 0;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { PreparedStatement.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("executeQuery")) return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.ResultSet.class}, (r, call, values) -> {
                        if (call.getName().equals("getMetaData")) return metadata;
                        if (call.getName().equals("close")) return null;
                        throw new UnsupportedOperationException(call.getName());
                    });
                    if (method.getName().equals("close")) return null;
                    throw new UnsupportedOperationException(method.getName());
                });
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    assertEquals("prepareStatement", method.getName());
                    assertEquals(sql, args[0]);
                    return statement;
                });
        assertEquals(List.of(new ResultColumn("LABEL", "java.lang.String")),
                new JdbcMetadataReader().read(connection, sql, "", TypeNameMappings.empty(), stage -> {}, SqlInput.Dialect.MYSQL));
    }

    @Test void unsupportedQuoteSyntaxIsReportedByDomaWithoutARewriteOrRawFallback() {
        Connection connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{throw new AssertionError("No JDBC call after a Doma parser error");});
        var error=assertThrows(GenerationException.class,()->new JdbcMetadataReader().read(connection,"SELECT 'it\\'s' AS label","",TypeNameMappings.empty(),stage->{},SqlInput.Dialect.MYSQL));
        assertTrue(error.getMessage().contains("DOMA2101"));
    }
}
