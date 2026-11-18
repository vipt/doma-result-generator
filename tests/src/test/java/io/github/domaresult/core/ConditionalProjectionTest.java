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
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConditionalProjectionTest {
    // Generic reproduction: CTE, UNION, repeated projection choice and application-only bind comments.
    private static final String UNION_SQL = """
            WITH data_set AS (SELECT id, name, exact_name FROM employee)
            SELECT id
            /*%if @not.present.Options@exactName() */
              , CASE WHEN exact_name IS NULL OR exact_name=' ' THEN name ELSE exact_name END AS full_name
            /*%else*/
              , name AS full_name
            /*%end*/
            FROM data_set WHERE AND id >= /* @not.present.Values@MINIMUM */0
            UNION ALL
            SELECT id
            /*%if @not.present.Options@exactName() */
              , CASE WHEN exact_name IS NULL OR exact_name=' ' THEN name ELSE exact_name END AS full_name
            /*%else*/
              , name AS full_name
            /*%end*/
            FROM employee
            """;

    @Test void verifiesBothProjectionBranchesAndGeneratesWithoutEditingTheTemplateOrExecutingRows() throws Exception {
        try (var real = database()) {
            List<String> prepared = new ArrayList<>();
            AtomicInteger closed = new AtomicInteger();
            var columns = read(guard(real, prepared, closed, () -> {}), UNION_SQL, TypeNameMappings.empty(), new PreviewExecution.Cancellation());
            assertEquals(List.of(new ResultColumn("ID", "java.lang.Long"), new ResultColumn("FULL_NAME", "java.lang.String")), columns);
            assertEquals(2, prepared.size()); // A repeated condition is consistent across UNION branches.
            assertTrue(prepared.stream().allMatch(sql -> sql.startsWith(";WITH")));
            assertEquals(2, closed.get());
            assertTrue(prepared.get(0).contains("CASE WHEN"));
            assertFalse(prepared.get(1).contains("CASE WHEN"));
            String generated = new EntityGenerator().generate("example.result", "EmployeeResult", columns);
            assertTrue(generated.contains("private String fullName;"));
            assertEquals(UNION_SQL, SqlInput.validate(UNION_SQL, SqlInput.Dialect.SQLSERVER));
        }
    }

    @Test void independentChoicesAndElseifBranchesAreAllDescribedIncludingTheImplicitEmptyBranch() throws Exception {
        String sql = """
                SELECT
                /*%if first*/ CAST(1 AS BIGINT) /*%elseif second*/ CAST(2 AS BIGINT) /*%else*/ CAST(3 AS BIGINT) /*%end*/ AS id,
                /*%if other*/ 'a' /*%else*/ 'b' /*%end*/ AS name
                FROM employee WHERE 1=1 /*%if filter*/ AND id>0 /*%end*/
                """;
        try (var real = database()) {
            List<String> prepared = new ArrayList<>();
            AtomicInteger closed = new AtomicInteger();
            assertEquals(2, read(guard(real, prepared, closed, () -> {}), sql, TypeNameMappings.empty(), new PreviewExecution.Cancellation()).size());
            assertEquals(6, prepared.size());
            assertEquals(6, closed.get());
            assertTrue(prepared.stream().allMatch(value -> value.contains("AND id>0")));
        }
        assertEquals(2, SqlInput.metadataVariants("SELECT id /*%if extra*/, name /*%end*/ FROM employee", SqlInput.Dialect.STANDARD).size());
    }

    @ParameterizedTest @ValueSource(strings = {
            "SELECT id, name FROM /*%if flag*/ employee /*%else*/ employee_copy /*%end*/",
            "SELECT id, name FROM employee ORDER BY /*%if flag*/ id /*%else*/ name /*%end*/",
            "SELECT e.id, e.name FROM employee e /*%if flag*/ LEFT JOIN employee_copy other ON e.id=other.id /*%end*/"
    })
    void permitsNonProjectionBranchesParsedByDomaAndChecksTheirResultMetadata(String sql) throws Exception {
        assertNotNull(new org.seasar.doma.internal.jdbc.sql.SqlParser(sql).parse());
        assertEquals(sql, SqlInput.validate(sql, SqlInput.Dialect.SQLSERVER));
        try (var real = database()) {
            List<String> prepared = new ArrayList<>();
            assertEquals(List.of(new ResultColumn("ID", "java.lang.Long"), new ResultColumn("NAME", "java.lang.String")),
                    read(guard(real, prepared, new AtomicInteger(), () -> {}), sql, TypeNameMappings.empty(), new PreviewExecution.Cancellation()));
            assertEquals(2, prepared.size());
        }
    }

    @Test void malformedExpressionsAreParsedByDomaWithoutLoadingClassesOrExposingExpressionText() {
        var failure = assertThrows(GenerationException.class, () -> SqlInput.validate(
                "SELECT\n/*%if privateFlag == */ id /*%else*/ id /*%end*/ AS id FROM employee"));
        assertTrue(failure.getMessage().contains("DOMA"));
        assertTrue(failure.getMessage().contains("line 2"));
        assertFalse(failure.getMessage().contains("privateFlag"));
    }

    @ParameterizedTest @ValueSource(strings = {
            "SELECT /*%if flag*/ id AS first_value /*%else*/ id AS second_value /*%end*/ FROM employee",
            "SELECT id /*%if flag*/, name /*%end*/ FROM employee",
            "SELECT /*%if flag*/ id, name /*%else*/ name, id /*%end*/ FROM employee",
            "SELECT /*%if flag*/ CAST(1 AS BIGINT) /*%else*/ CAST(1 AS INTEGER) /*%end*/ AS id FROM employee"
    })
    void refusesToGenerateWhenBranchColumnNamesCountOrderOrTypesDiffer(String sql) throws Exception {
        try (var real = database()) {
            List<String> prepared = new ArrayList<>();
            AtomicInteger closed = new AtomicInteger();
            MetadataException failure = assertThrows(MetadataException.class, () ->
                    read(guard(real, prepared, closed, () -> {}), sql, TypeNameMappings.empty(), new PreviewExecution.Cancellation()));
            assertTrue(failure.getMessage().contains("different column names, order or JDBC types"));
            assertFalse(failure.getMessage().contains("flag"));
            assertEquals(2, closed.get());
        }
    }

    @Test void typeMappingOverridesCannotHideDifferentJdbcTypes() throws Exception {
        try (var real = database()) {
            var mappings = TypeNameMappings.parse(".*=java.lang.String");
            assertThrows(MetadataException.class, () -> read(real,
                    "SELECT /*%if flag*/ CAST(1 AS BIGINT) /*%else*/ CAST(1 AS INTEGER) /*%end*/ AS id",
                    mappings, new PreviewExecution.Cancellation()));
        }
    }

    @Test void filterElseBranchesAndNestedProjectionChoicesAreHandledByTheOfficialBuilder() throws Exception {
        String sql = """
                SELECT /*%if outer*/ /*%if inner*/ id /*%else*/ CAST(0 AS BIGINT) /*%end*/
                       /*%else*/ CAST(1 AS BIGINT) /*%end*/ AS id
                FROM employee WHERE /*%if active*/ AND id>0 /*%elseif disabled*/ OR id<0 /*%else*/ id=0 /*%end*/
                """;
        try (var real = database()) {
            List<String> prepared = new ArrayList<>();
            read(guard(real, prepared, new AtomicInteger(), () -> {}), sql, TypeNameMappings.empty(), new PreviewExecution.Cancellation());
            assertEquals(9, prepared.size()); // Identical SQL from unreachable nested choices is deduplicated.
            assertTrue(prepared.stream().anyMatch(value -> value.contains("id=0")));
        }
    }

    @Test void tooManyCombinationsStopBeforeAnyDatabaseOperationWithoutExposingExpressions() {
        StringBuilder sql = new StringBuilder("SELECT 1 AS id");
        for (int i = 0; i < 7; i++) sql.append(" /*%if privateFlag").append(i).append("*/,1 AS value").append(i).append(" /*%end*/");
        var failure = assertThrows(GenerationException.class, () -> SqlInput.metadataVariants(sql.toString(), SqlInput.Dialect.STANDARD));
        assertTrue(failure.getMessage().contains("64"));
        assertFalse(failure.getMessage().contains("privateFlag"));
    }

    @Test void cancellationBetweenBranchDescriptionsStopsFurtherPreparesAndClosesTheCurrentStatement() throws Exception {
        try (var real = database()) {
            var cancellation = new PreviewExecution.Cancellation();
            List<String> prepared = new ArrayList<>();
            AtomicInteger closed = new AtomicInteger();
            try {
                assertThrows(CancellationException.class, () -> read(guard(real, prepared, closed, () -> Thread.currentThread().interrupt()),
                        "SELECT /*%if flag*/ id /*%else*/ CAST(0 AS BIGINT) /*%end*/ AS id FROM employee",
                        TypeNameMappings.empty(), cancellation));
            } finally { Thread.interrupted(); }
            assertEquals(1, prepared.size());
            assertEquals(1, closed.get());
        }
    }

    private static List<ResultColumn> read(Connection connection, String sql, TypeNameMappings mappings,
            PreviewExecution.Cancellation cancellation) throws Exception {
        return new JdbcMetadataReader().read(connection, sql, "example.EmployeeResult", mappings, stage -> {}, SqlInput.Dialect.SQLSERVER, cancellation);
    }

    private static Connection database() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MSSQLServer");
        try (var setup = connection.createStatement()) {
            setup.execute("CREATE TABLE employee (id BIGINT, name VARCHAR(50), exact_name VARCHAR(50))");
            setup.execute("CREATE TABLE employee_copy (id BIGINT, name VARCHAR(50), exact_name VARCHAR(50))");
        }
        return connection;
    }

    private static Connection guard(Connection real, List<String> prepared, AtomicInteger closed, Runnable afterMetadata) {
        return (Connection) Proxy.newProxyInstance(ConditionalProjectionTest.class.getClassLoader(), new Class<?>[] { Connection.class }, (p, method, args) -> {
            assertEquals("prepareStatement", method.getName());
            String sql = (String) args[0];
            prepared.add(sql);
            assertFalse(sql.contains("/*%"));
            PreparedStatement statement = real.prepareStatement(sql.startsWith(";") ? sql.substring(1) : sql);
            return Proxy.newProxyInstance(ConditionalProjectionTest.class.getClassLoader(), new Class<?>[] { PreparedStatement.class }, (s, call, values) -> {
                if (call.getName().startsWith("execute")) fail("Metadata lookup must not execute the SELECT");
                if (call.getName().equals("close")) closed.incrementAndGet();
                try {
                    Object result = call.invoke(statement, values);
                    if (call.getName().equals("getMetaData")) afterMetadata.run();
                    return result;
                } catch (InvocationTargetException e) { throw e.getCause(); }
            });
        });
    }
}
