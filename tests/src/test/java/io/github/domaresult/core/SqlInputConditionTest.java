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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class SqlInputConditionTest {
    // Generic regression SQL: nested filters, IN-list fragments, a correlated subquery and UNION.
    private static final String FILTER_SQL = """
            SELECT data_set.id AS employee_id, data_set.name AS employee_name
            FROM (
                SELECT e.id, e.name FROM employee e
                WHERE 1=1
                /*%if @isNotBlank(filter.name) */
                  AND e.name LIKE /* @infix(filter.name) */'a' ESCAPE '$'
                /*%end*/
                /*%if @isNotEmpty(filter.first) || @isNotEmpty(filter.second) */
                  AND e.status IN (''
                    /*%if @isNotEmpty(filter.first) */ ,'A' /*%end*/
                    /*%if @isNotEmpty(filter.second) */ ,'B' /*%end*/
                  )
                /*%end*/
                  AND EXISTS (
                    SELECT 1 FROM department d WHERE d.id = e.department_id
                    /*%if filter.department != null */
                      AND d.name = /* filter.department */'a'
                    /*%end*/
                  )
                UNION ALL
                SELECT e.id, e.name FROM employee e
                WHERE e.status = /* @example.Flags@ACTIVE.getValue() */'A'
                /*%if filter.ids.size() > 0 */
                  AND e.id IN /* filter.ids */(0)
                /*%end*/
            ) data_set
            WHERE 1=1
            /*%if filter.minimum != null */ AND data_set.id >= /* filter.minimum */0 /*%end*/
            ORDER BY data_set.id
            """;

    @ParameterizedTest @EnumSource(SqlInput.Dialect.class)
    void acceptsNestedFiltersWithoutChangingAnySqlOrEvaluatingApplicationExpressions(SqlInput.Dialect dialect) {
        assertEquals(FILTER_SQL, SqlInput.validate(FILTER_SQL, dialect));
        assertEquals(FILTER_SQL, SqlInput.validate(SqlInput.validate(FILTER_SQL, dialect), dialect));
    }

    @ParameterizedTest @ValueSource(strings = {
            "SELECT id FROM employee WHERE /*%if active*/ id = 1 /*%end*/",
            "SELECT id FROM employee WHERE id IN (0 /*%if active*/ ,1 /*%end*/)",
            "SELECT id FROM employee WHERE (/*%if active*/ id = 1 /*%end*/)",
            "SELECT department_id, COUNT(*) FROM employee GROUP BY department_id HAVING 1=1 /*%if active*/ AND COUNT(*) > 0 /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%if active*/ AND EXISTS (SELECT 1 FROM department WHERE id=1) /*%end*/",
            "SELECT (SELECT COUNT(*) FROM employee WHERE /*%if active*/ id>0 /*%end*/) AS total",
            "WITH data_set AS (SELECT id FROM employee WHERE 1=1 /*%if active*/ AND id>0 /*%end*/) SELECT id FROM data_set",
            "SELECT ROW_NUMBER() OVER (ORDER BY id) AS row_no FROM employee WHERE 1=1 /*%if active*/ AND id>0 /*%end*/",
            "SELECT @where AS value FROM #example WHERE 1=1 /*%if active*/ AND @having = 0 /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%if active*/ AND note = 'ORDER BY; /*%else*/' /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%if active*/ AND id > 0 /* GROUP BY */ -- UNION\n /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%if active*/ /*%if nested*/ AND id>0 /*%end*/ /*%end*/"
    })
    void retainsFixedProjectionFilterSql(String sql) {
        assertEquals(sql, SqlInput.validate(sql, SqlInput.Dialect.SQLSERVER));
    }

    @ParameterizedTest @ValueSource(strings = {
            "SELECT /*%if active*/ id, /*%end*/ name FROM employee",
            "SELECT id FROM /*%if active*/ employee /*%end*/",
            "SELECT id FROM employee /*%if active*/ WHERE id=1 /*%end*/",
            "SELECT id FROM employee WHERE id=1 ORDER BY /*%if active*/ id /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%if active*/ AND id>0 ORDER BY id /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%if active*/ AND id>0 GROUP BY id /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%if active*/ UNION ALL SELECT id FROM employee /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%if active*/ AND id>0 LIMIT 1 /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%if active*/ AND id>0 FOR UPDATE /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%if active*/ AND id>0 OPTION (RECOMPILE) /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%if active*/ AND id>0 HAVING COUNT(*)>1 /*%end*/",
            "SELECT id FROM employee WHERE EXISTS (SELECT id FROM department /*%if active*/ WHERE id=1 /*%end*/)",
            "SELECT id FROM employee WHERE EXISTS (SELECT /*%if active*/ id /*%end*/ FROM department)",
            "SELECT @where /*%if active*/ ,id /*%end*/ FROM employee",
            "SELECT employee.where /*%if active*/ ,id /*%end*/ FROM employee",
            "SELECT id FROM #where /*%if active*/ JOIN department ON 1=1 /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%if active*/ AND id>0 /*%else*/ AND id<0 /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%if active*/ AND id>0 /*%elseif other*/ AND id<0 /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%for id:ids*/ AND id>0 /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%if*/ AND id>0 /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%if active*/ AND id>0",
            "SELECT id FROM employee WHERE 1=1 /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%if active*/ AND (id>0 /*%end*/)",
            "SELECT id FROM employee WHERE (/*%if active*/ id>0) /*%end*/",
            "SELECT id FROM employee WHERE EXISTS (SELECT 1 FROM department WHERE /*%if active*/ id>0) /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%if active*/ AND id>0; /*%end*/",
            "SELECT id FROM employee WHERE 1=1; /*%if active*/ /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%if active*/ DELETE FROM employee /*%end*/",
            "SELECT id FROM employee WHERE 1=1 /*%if active*/ AND id>0 /*%end*/; DELETE FROM employee"
    })
    void rejectsProjectionChangesUnsupportedBranchesAndCrossedScopes(String sql) {
        assertThrows(GenerationException.class, () -> SqlInput.validate(sql, SqlInput.Dialect.SQLSERVER));
    }

    @Test void diagnosticsLocateTheDirectiveWithoutDisclosingItsExpression() {
        String sql = "SELECT id\nFROM employee\n/*%if private_expression*/ WHERE id=1 /*%end*/";
        var failure = assertThrows(GenerationException.class, () -> SqlInput.validate(sql));
        assertTrue(failure.getMessage().contains("line 3"));
        assertTrue(failure.getMessage().contains("WHERE or HAVING"));
        assertFalse(failure.getMessage().contains("private_expression"));
    }

    @Test void directivesInsideStringsIdentifiersAndNestedCommentsRemainText() {
        String sql = """
                SELECT '/*%if active*/' AS [/*%end*/]
                FROM employee WHERE 1=1
                /*%if active*/
                  AND note = N'/*%else*/'
                  /* outer /*%end*/ comment */
                /*%end*/
                """;
        assertEquals(sql, SqlInput.validate(sql, SqlInput.Dialect.SQLSERVER));
        String dollar = "SELECT $$/*%if active*/$$ AS note FROM employee WHERE 1=1 /*%if active*/ AND id>0 /*%end*/";
        assertEquals(dollar, SqlInput.validate(dollar, SqlInput.Dialect.POSTGRESQL));
        String escaped = "SELECT 'it\\'s /*%if active*/' AS note FROM employee WHERE 1=1 /*%if active*/ AND id>0 /*%end*/";
        assertEquals(escaped, SqlInput.validate(escaped, SqlInput.Dialect.MYSQL));
    }

    @Test void expansionAndFilterConditionsWorkTogetherAndRemainIdempotent() {
        String sql = "SELECT /*%expand \"e\"*/* FROM employee e WHERE 1=1 /*%if active*/ AND e.id>0 /*%end*/; -- end";
        String prepared = "SELECT e.* FROM employee e WHERE 1=1 /*%if active*/ AND e.id>0 /*%end*/ -- end";
        assertEquals(prepared, SqlInput.validate(sql));
        assertEquals(prepared, SqlInput.validate(prepared));
    }

    @ParameterizedTest @ValueSource(strings = { "MSSQLServer", "Oracle" })
    void readsMetadataFromTheUnmodifiedTemplateWithoutExecutingOrFetchingRows(String mode) throws Exception {
        try (var real = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=" + mode)) {
            try (var setup = real.createStatement()) {
                setup.execute("CREATE TABLE employee (id BIGINT, name VARCHAR(50), status VARCHAR(1), department_id INTEGER)");
                setup.execute("CREATE TABLE department (id INTEGER, name VARCHAR(50))");
            }
            Connection guarded = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class }, (proxy, method, args) -> {
                assertEquals("prepareStatement", method.getName());
                String jdbcSql = (String) args[0];
                assertFalse(jdbcSql.contains("/*%if"));
                assertFalse(jdbcSql.contains("/*%end"));
                assertTrue(jdbcSql.contains("e.status IN (''"));
                assertTrue(jdbcSql.contains("'A'"));
                assertTrue(jdbcSql.contains("'B'"));
                PreparedStatement statement = (PreparedStatement) method.invoke(real, args);
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { PreparedStatement.class }, (p, call, values) -> {
                    if (call.getName().startsWith("execute")) fail("Metadata lookup must not execute the SELECT");
                    try { return call.invoke(statement, values); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
            });
            assertEquals(List.of(new ResultColumn("EMPLOYEE_ID", "java.lang.Long"), new ResultColumn("EMPLOYEE_NAME", "java.lang.String")),
                    new JdbcMetadataReader().read(guarded, FILTER_SQL, "", TypeNameMappings.empty(), stage -> {}, SqlInput.Dialect.SQLSERVER));
        }
    }
}
