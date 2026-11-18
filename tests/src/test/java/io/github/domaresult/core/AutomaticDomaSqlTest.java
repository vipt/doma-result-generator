/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Type;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class AutomaticDomaSqlTest {
    public static class Condition {
        public List<Long> ids = List.of(1L, 2L);
        public String name;
        public LocalDate date;
        public Nested nested;
        public boolean enabled() {
            assertTrue(Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame -> frame.getClassName().equals("org.seasar.doma.internal.expr.ExpressionEvaluator")));
            return true;
        }
        public String getDateString() { return date == null ? null : date.toString(); }
    }
    public static class Nested { public String code; }
    public static class Constants { public static String code() { return "sample"; } }
    public record RecordCondition(String name, LocalDate date) {}
    public static class NoDefaultConstructor { public NoDefaultConstructor(String a, String b) {} }
    public static class GenericParameters { List<Long> ids; Optional<String> name; }

    @Test void suppliesScalarDummyValuesAndDomaRemovesLeadingAndInsideASubquery() throws Exception {
        String sql="SELECT e.id, (SELECT COUNT(*) FROM employee sub WHERE AND sub.id=e.id AND sub.name=/* name */'sample') AS total FROM employee e WHERE AND e.id=/* id */1";
        var prepared=prepare(sql,Map.of());
        assertFalse(prepared.sql().matches("(?is).*WHERE\\s+AND\\b.*"));
        assertEquals(2,prepared.parameterCount());
        assertTrue(sql.contains("WHERE AND"));
        try(var connection=database()) {
            assertEquals(2,new JdbcMetadataReader().read(connection,sql).size());
        }
    }

    @Test void infersStandaloneListArgumentsForSizeLoopsAndBindParameters() throws Exception {
        String sql="""
                SELECT id, name FROM employee
                WHERE /*%if ids.size() > 0*/
                  AND (/*%for item : ids*/ id=/*item*/1
                    /*%if item_has_next*/ /*# "OR" */ /*%end*/
                  /*%end*/)
                /*%end*/
                """;
        var prepared=prepare(sql,Map.of());
        assertEquals(1,prepared.parameterCount());
        assertFalse(prepared.sql().contains("/*%"));
        try(var connection=database()) { assertEquals(2,new JdbcMetadataReader().read(connection,sql).size()); }
    }

    @Test void officialEvaluatorHandlesDtoMethodsLoopFlagsAndEmbeddedOr() throws Exception {
        String sql="""
                SELECT id, name FROM employee WHERE
                /*%if condition.enabled() && condition.ids.size() > 0*/
                AND (/*%for item : condition.ids*/ id=/*item*/1
                  /*%if item_has_next*/ /*# "OR" */ /*%end*/
                /*%end*/)
                /*%end*/
                AND name=/*condition.name*/'sample'
                """;
        var prepared=prepare(sql,Map.of("condition",Condition.class));
        assertEquals(3,prepared.parameterCount());
        assertTrue(prepared.sql().contains("OR"));
        try(var connection=database()) {
            var columns=read(connection,sql,Map.of("condition",Condition.class));
            assertEquals(List.of(new ResultColumn("ID","java.lang.Long"),new ResultColumn("NAME","java.lang.String")),columns);
        }
    }

    @Test void initializesNestedDtoFieldsDatesAndComputedGettersWithoutArgumentInput() {
        String sql="SELECT 1 WHERE /*%if condition.getDateString() != null*/ AND 'x'=/*condition.nested.code*/'x' /*%end*/";
        var prepared=prepare(sql,Map.of("condition",Condition.class));
        assertEquals(1,prepared.parameterCount());
        assertTrue(prepared.sql().contains("WHERE"));
    }

    @Test void supportsRecordParametersAndGenericCollections() throws Exception {
        var record=prepare("SELECT 1 WHERE 'x'=/*condition.name*/'x'",Map.of("condition",RecordCondition.class));
        assertEquals(1,record.parameterCount());
        Type list=GenericParameters.class.getDeclaredField("ids").getGenericType();
        var prepared=prepare("SELECT 1 WHERE 1 IN /*ids*/(1,2)",Map.of("ids",list));
        assertEquals(1,prepared.parameterCount());
        try(var connection=database()) { assertEquals(1,read(connection,"SELECT 1 AS id WHERE 1 IN /*ids*/(1)",Map.of("ids",list)).size()); }
    }

    @Test void evaluatesApplicationConstantsThroughDomaAndTheProjectClassLoader() {
        String sql="SELECT 1 WHERE 'sample'=/*@"+Constants.class.getName()+"@code()*/'sample'";
        assertEquals(1,prepare(sql,Map.of()).parameterCount());
    }

    @Test void officialLiteralAndEmbeddedDirectivesAreSupported() throws Exception {
        String sql="SELECT /*^id*/1 AS id FROM employee WHERE /*# \"id >= 0\" */";
        var prepared=prepare(sql,Map.of());
        assertEquals(0,prepared.parameterCount());
        assertTrue(prepared.sql().contains("id >= 0"));
        try(var connection=database()) { assertEquals(1,new JdbcMetadataReader().read(connection,sql).size()); }
    }

    @Test void preservesTypedDummyBindingWhenNoRowsMatch() throws Exception {
        try(var connection=database()) {
            var columns=new JdbcMetadataReader().read(connection,"SELECT id,name FROM employee WHERE AND id=/*id*/999999");
            assertEquals(2,columns.size());
        }
    }

    @Test void reportsMissingDtoTypeWithoutInventingItsFieldsOrAskingForArguments() {
        var failure=assertThrows(GenerationException.class,()->prepare("SELECT 1 WHERE 1=/*condition.id*/1",Map.of()));
        assertTrue(failure.getMessage().contains("Build the project"));
        assertFalse(failure.getMessage().contains("DOMA2111"));
    }

    @Test void reportsUnsupportedConstructorsWithoutLeakingSqlOrPrivateValues() {
        var failure=assertThrows(GenerationException.class,()->prepare("SELECT 'private marker' WHERE /*%if condition != null*/1=1/*%end*/",Map.of("condition",NoDefaultConstructor.class)));
        assertFalse(failure.getMessage().contains("private marker"));
        assertTrue(failure.getMessage().contains("constructor"));
    }

    @Test void syntaxErrorsRemainDomaErrorsWithoutRawSqlOrValues() {
        var failure=assertThrows(GenerationException.class,()->prepare("SELECT 'private marker' /*%if missing*/",Map.of()));
        assertTrue(failure.getMessage().contains("DOMA"));
        assertFalse(failure.getMessage().contains("private marker"));
    }

    @Test void projectionBranchesAreSelectedByDomaRatherThanCombined() {
        String sql="SELECT /*%if flag*/ 1 AS first_value /*%else*/ 'x' AS second_value /*%end*/";
        var prepared=prepare(sql,Map.of("flag",Boolean.class));
        assertFalse(prepared.sql().contains("first_value"));
        assertTrue(prepared.sql().contains("second_value"));
    }

    @Test void dmlInjectedThroughAnEmbeddedValueNeverReachesJdbc() {
        assertThrows(GenerationException.class,()->prepare("SELECT 1 /*# \"; DELETE FROM employee\" */",Map.of()));
    }

    @Test void nativeCtesWithParametersNeedNoSqlWrapperOrSourceEdit() throws Exception {
        String sql="WITH selected AS (SELECT id,name FROM employee WHERE AND id=/*id*/1) SELECT id,name FROM selected;";
        var prepared=prepare(sql,Map.of());
        assertTrue(prepared.sql().startsWith("WITH selected"));
        assertEquals(1,prepared.parameterCount());
        assertFalse(prepared.sql().contains("FMTONLY"));
        try(var connection=database()) { assertEquals(2,new JdbcMetadataReader().read(connection,sql).size()); }
    }

    @Test void closesStatementsAndPreservesVendorCodesWhenTheStandardBinderFails() {
        SQLException expected=new SQLException("private connection details","S0001",12345);
        int[] closes={0};
        PreparedStatement statement=(PreparedStatement)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
            if(m.getName().equals("setInt")) {
                assertTrue(Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame->frame.getClassName().equals("org.seasar.doma.internal.jdbc.command.PreparedSqlParameterBinder")));
                throw expected;
            }
            if(m.getName().equals("close")){closes[0]++;return null;}
            throw new AssertionError("Unexpected statement call: "+m.getName());
        });
        Connection connection=(Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            assertEquals("prepareStatement",m.getName());return statement;
        });
        assertSame(expected,assertThrows(SQLException.class,()->new JdbcMetadataReader().read(connection,"SELECT 1 WHERE 1=/*id*/1")));
        assertEquals(1,closes[0]);
    }

    @Test void cancellationStopsBeforeConstructingDummyObjects() {
        Thread.currentThread().interrupt();
        try { assertThrows(java.util.concurrent.CancellationException.class,()->prepare("SELECT 1",Map.of())); }
        finally { Thread.interrupted(); }
    }

    private static DomaPreparedSelect prepare(String sql,Map<String,Type> types) {
        return DomaSql.prepare(sql,SqlInput.Dialect.SQLSERVER,"",AutomaticDomaSqlTest.class.getClassLoader(),types,new PreviewExecution.Cancellation());
    }
    private static List<ResultColumn> read(Connection connection,String sql,Map<String,Type> types)throws SQLException {
        return new JdbcMetadataReader().read(connection,sql,"",TypeNameMappings.empty(),stage->{},SqlInput.Dialect.STANDARD,
                new PreviewExecution.Cancellation(),"jdbc:h2:mem:test",AutomaticDomaSqlTest.class.getClassLoader(),types);
    }
    private static Connection database()throws SQLException {
        var connection=DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID());
        try(var setup=connection.createStatement()) {
            setup.execute("CREATE TABLE employee(id BIGINT,name VARCHAR(50))");
            setup.execute("INSERT INTO employee VALUES(1,'sample'),(2,'sample')");
        }
        return connection;
    }
}
