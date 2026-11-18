/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.seasar.doma.internal.jdbc.sql.NodePreparedSqlBuilder;
import org.seasar.doma.internal.jdbc.sql.SqlParser;
import org.seasar.doma.jdbc.Config;
import org.seasar.doma.jdbc.SqlKind;
import org.seasar.doma.jdbc.dialect.Dialect;
import org.seasar.doma.jdbc.dialect.MssqlDialect;

class OfficialDomaBackendTest {
    private static final Config CONFIG = new Config() {
        public DataSource getDataSource() { throw new AssertionError("SQL assembly must not connect"); }
        public Dialect getDialect() { return new MssqlDialect(); }
    };

    @ParameterizedTest @ValueSource(strings = {
            "SELECT id FROM employee WHERE AND id>0",
            "SELECT id FROM employee WHERE OR id>0",
            "SELECT id FROM employee WHERE -- optional conditions\n AND id>0",
            "SELECT id FROM employee WHERE AND EXISTS (SELECT id FROM detail WHERE AND id>0)",
            "SELECT department_id, COUNT(*) FROM employee GROUP BY department_id HAVING AND COUNT(*)>0"
    })
    void followsTheActualDomaSqlBuilderForLeadingLogicalOperators(String source) {
        String official = new NodePreparedSqlBuilder(CONFIG, SqlKind.SELECT, null)
                .build(new SqlParser(source).parse(), Function.identity()).getRawSql();
        assertEquals(source, SqlInput.validate(source, SqlInput.Dialect.SQLSERVER));
        assertEquals(official, SqlInput.forMetadata(source, SqlInput.Dialect.SQLSERVER));
        assertFalse(official.matches("(?s).*\\b(?:WHERE|HAVING)\\s+(?:AND|OR)\\b.*"));
    }

    @Test void usesTestValuesWithoutLoadingApplicationClassesOrEvaluatingConditions() {
        String source = "SELECT /* @not.present.Values@ID */1 AS id WHERE /*%if @not.present.Functions@enabled() */ AND 1=1 /*%end*/";
        String prepared = SqlInput.forMetadata(source, SqlInput.Dialect.SQLSERVER);
        assertFalse(prepared.contains("/*%if"));
        assertTrue(prepared.contains("1 AS id"));
        assertTrue(prepared.contains("1=1"));
        assertFalse(prepared.matches("(?s).*WHERE\\s+AND\\b.*"));
    }

    @Test void preservesOpaqueSqlAndDoesNotConfuseUserTextWithAdapterPlaceholders() {
        String literal = "'__doma_result_opaque_0__ WHERE AND /*%else*/'";
        String source = "SELECT " + literal + " AS [WHERE AND] FROM employee WHERE /* outer /* inner */ end */ AND id>0";
        String prepared = SqlInput.forMetadata(source, SqlInput.Dialect.SQLSERVER);
        assertTrue(prepared.contains(literal));
        assertTrue(prepared.contains("[WHERE AND]"));
        assertTrue(prepared.contains("/* outer /* inner */ end */"));
        assertFalse(prepared.contains("AND id>0"));
        assertEquals(prepared, SqlInput.forMetadata(prepared, SqlInput.Dialect.SQLSERVER));
    }

    @Test void generatesFromNormalizedScalarSubqueriesWithoutExecutingTheSelect() throws Exception {
        String source = """
                SELECT h.id,
                  (SELECT CASE WHEN COUNT(*)>0 THEN 1 ELSE 0 END FROM detail d
                   WHERE AND d.header_id=h.id AND d.state=/* @not.present.States@ACTIVE */'A') AS detail_count,
                  CAST(NULL AS VARCHAR(20)) AS optional_code
                FROM header h WHERE AND h.deleted='0'
                /*%if filter.minimum != null*/ AND h.id >= /* filter.minimum */0 /*%end*/
                """;
        try (var real = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MSSQLServer")) {
            try (var setup = real.createStatement()) {
                setup.execute("CREATE TABLE header (id BIGINT, deleted VARCHAR(1))");
                setup.execute("CREATE TABLE detail (header_id BIGINT, state VARCHAR(1))");
            }
            assertThrows(java.sql.SQLException.class, () -> real.prepareStatement(source));
            Connection guarded = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (p, method, args) -> {
                assertEquals("prepareStatement", method.getName());
                assertFalse(((String) args[0]).matches("(?s).*WHERE\\s+AND\\b.*"));
                PreparedStatement statement = real.prepareStatement((String) args[0]);
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class}, (s, call, values) -> {
                    if (call.getName().startsWith("execute")) fail("Metadata lookup must not execute the SELECT");
                    try { return call.invoke(statement, values); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
            });
            var columns = new JdbcMetadataReader().read(guarded, source, "", TypeNameMappings.empty(), stage -> {}, SqlInput.Dialect.SQLSERVER);
            assertEquals(List.of(new ResultColumn("ID", "java.lang.Long"), new ResultColumn("DETAIL_COUNT", "java.lang.Integer"),
                    new ResultColumn("OPTIONAL_CODE", "java.lang.String")), columns);
            String generated = new EntityGenerator().generate("example.result", "HeaderResult", columns);
            assertTrue(generated.contains("private Long id;"));
            assertTrue(generated.contains("private Integer detailCount;"));
            assertTrue(generated.contains("@domaGenerated"));
            assertEquals(source, SqlInput.validate(source, SqlInput.Dialect.SQLSERVER));
        }
    }
}
