/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.seasar.doma.internal.expr.ExpressionEvaluator;
import org.seasar.doma.internal.expr.Value;
import org.seasar.doma.internal.jdbc.sql.NodePreparedSqlBuilder;
import org.seasar.doma.internal.jdbc.sql.SqlParser;
import org.seasar.doma.jdbc.*;
import org.seasar.doma.jdbc.dialect.MssqlDialect;

class NativeDomaEvaluationTest {
    private final ClassLoader loader = getClass().getClassLoader();
    private static final String LOOP = """
            SELECT id, name FROM employee WHERE /*%if ids.size()>0*/ (
              /*%for item:ids*/ id=/*item*/999
                /*%if item_has_next*/ /*# separator*/ /*%end*/
              /*%end*/)
            /*%else*/ id<0 /*%end*/
            """;

    @ParameterizedTest @ValueSource(ints = {0, 1, 3, 300})
    void matchesAnUnmodifiedOfficialBuilderUsingTheExactProvidedList(int count) {
        var ids = new ArrayList<Long>(); for (int i=0;i<count;i++) ids.add((long)i);
        Map<String, Value> arguments = Map.of("ids", new Value(List.class, ids), "separator", new Value(String.class, "OR"));
        Config config = new Config() {
            public DataSource getDataSource() { throw new AssertionError(); }
            public MssqlDialect getDialect() { return new MssqlDialect(); }
        };
        String official = new NodePreparedSqlBuilder(config, SqlKind.SELECT, null,
                new ExpressionEvaluator(arguments, config.getDialect().getExpressionFunctions(), config.getClassHelper()), SqlLogType.NONE)
                .build(new SqlParser(LOOP).parse(), Function.identity()).getRawSql();
        DomaPreparedSelect prepared = SqlInput.prepare(LOOP, SqlInput.Dialect.SQLSERVER, arguments, loader, "");
        assertEquals(official, prepared.sql());
        assertEquals(count, prepared.parameterCount());
        assertEquals(Math.max(0,count-1), java.util.regex.Pattern.compile("\\bOR\\b").matcher(prepared.sql()).results().count());
    }

    @ParameterizedTest @ValueSource(booleans = {true,false})
    void takesTheSelectedProjectionAndNeverDescribesInventedAlternatives(boolean enabled) throws Exception {
        try (var real = database()) {
            var plan = prepare("SELECT id /*%if enabled*/, name /*%end*/ FROM employee",
                    "{\"enabled\":{\"type\":\"boolean\",\"value\":"+enabled+"}}");
            var columns = read(real, plan);
            assertEquals(enabled?2:1, columns.size());
            assertTrue(plan.sql().contains("name") == enabled);
        }
    }

    @Test void nativeBindingUsesArgumentValuesAndNeverExecutesTheQuery() throws Exception {
        try (var real=database()) {
            List<Object> bound = new ArrayList<>();
            int[] preparedCount={0}, closed={0};
            Connection guarded=(Connection)Proxy.newProxyInstance(loader,new Class<?>[]{Connection.class},(p,m,a)->{
                assertEquals("prepareStatement",m.getName()); preparedCount[0]++;
                assertEquals(3, ((String)a[0]).chars().filter(c->c=='?').count());
                PreparedStatement statement=real.prepareStatement((String)a[0]);
                return Proxy.newProxyInstance(loader,new Class<?>[]{PreparedStatement.class},(s,c,v)->{
                    if(c.getName().startsWith("execute"))fail("Metadata lookup must not execute SELECT");
                    if(c.getName().startsWith("set")){assertEquals(bound.size()+1,v[0]);bound.add(v[1]);}
                    if(c.getName().equals("close"))closed[0]++;
                    try{return c.invoke(statement,v);}catch(InvocationTargetException e){throw e.getCause();}
                });
            });
            var plan=prepare(LOOP,"{\"ids\":{\"type\":\"java.util.List<java.lang.Long>\",\"value\":[10,20,30]},\"separator\":{\"type\":\"String\",\"value\":\"OR\"}}");
            assertEquals(2,read(guarded,plan).size());
            assertEquals(List.of(10L,20L,30L),bound);
            assertEquals(1,preparedCount[0]);assertEquals(1,closed[0]);
        }
    }

    @Test void typedBeansAndApplicationMethodsAreEvaluatedByDoma() {
        ExpressionFixture.calls=0;
        String source="SELECT /*%if filter.enabled && @io.github.domaresult.core.ExpressionFixture@enabled()*/ name /*%else*/ id /*%end*/ FROM employee WHERE name=/*filter.name*/'ignored' /*# @io.github.domaresult.core.ExpressionFixture@orderBy() */";
        var plan=prepare(source,"{\"filter\":{\"type\":\"io.github.domaresult.core.ExpressionFixture$Filter\",\"value\":{\"enabled\":true,\"name\":\"supplied\"}}}");
        assertEquals(2,ExpressionFixture.calls);
        assertTrue(plan.sql().contains("SELECT  name"));
        assertTrue(plan.sql().endsWith("ORDER BY name"));
        assertEquals(1,plan.parameterCount());
        assertFalse(plan.sql().contains("supplied"));
    }

    @Test void nativeLiteralAndDynamicAliasExpressionsUseTheArguments() {
        var plan=prepare("SELECT /*%expand alias*/* FROM employee e WHERE id=/*^id*/999", "{\"alias\":{\"type\":\"String\",\"value\":\"e\"},\"id\":{\"type\":\"long\",\"value\":42}}");
        assertTrue(plan.sql().contains("e.*"));assertTrue(plan.sql().contains("id=42"));
        assertEquals(0,plan.parameterCount());
        assertFalse(plan.sql().contains("999"));
    }

    @Test void missingArgumentsFailInsteadOfUsingTestLiteralsOrSampleElements() {
        for(String sql:List.of("SELECT /*id*/999 AS id",LOOP,"SELECT 1 WHERE /*%if missing*/ 1=1 /*%end*/")){
            var failure=assertThrows(GenerationException.class,()->prepare(sql,"{}"));
            assertTrue(failure.getMessage().contains("Missing argument"));
            assertFalse(failure.getMessage().contains("999"));
        }
    }

    @Test void typedNullListsDecimalsDatesAndPrimitiveValuesArePreserved() {
        var values=TemplateArguments.parse("{\"id\":{\"type\":\"java.lang.Long\",\"value\":null},\"amount\":{\"type\":\"java.math.BigDecimal\",\"value\":12.50},\"date\":{\"type\":\"java.time.LocalDate\",\"value\":\"2020-01-02\"},\"ids\":{\"type\":\"long[]\",\"value\":[1,2]}}",loader);
        assertNull(values.get("id").getValue());assertEquals(Long.class,values.get("id").getType());
        assertEquals(new java.math.BigDecimal("12.50"),values.get("amount").getValue());
        assertEquals(java.time.LocalDate.of(2020,1,2),values.get("date").getValue());
        assertArrayEquals(new long[]{1,2},(long[])values.get("ids").getValue());
    }

    @ParameterizedTest @ValueSource(strings={"[]","{bad}","{\"x\":{\"type\":\"boolean\",\"value\":null}}","{\"x\":{\"type\":\"missing.Type\",\"value\":\"private-value\"}}","{\"x\":{\"type\":\"String; int other\",\"value\":\"private-value\"}}"})
    void argumentErrorsDoNotExposeTheirValues(String json){
        var failure=assertThrows(GenerationException.class,()->TemplateArguments.parse(json,loader));
        assertFalse(failure.getMessage().contains("private-value"));
    }

    @Test void rejectsEmbeddedWritesAndMultipleQueriesAfterNativeEvaluation() {
        assertThrows(GenerationException.class,()->prepare("SELECT 1 /*# fragment*/", "{\"fragment\":{\"type\":\"String\",\"value\":\"INTO employee\"}}"));
        assertThrows(GenerationException.class,()->prepare("SELECT 1; SELECT 2","{}"));
        assertEquals("SELECT 1 AS id",prepare("SELECT 1 AS id;;","{}").sql());
    }

    @Test void defaultOfficialFunctionsAndProjectClassesAreNotRestrictedByACustomEvaluator() {
        var plan=prepare("SELECT id FROM employee WHERE /*%if @isNotBlank(name)*/ name LIKE /*@prefix(name)*/'ignored' /*%end*/", "{\"name\":{\"type\":\"String\",\"value\":\"test\"}}");
        assertEquals(1,plan.parameterCount());assertTrue(plan.sql().contains("LIKE ?"));
    }

    @Test void embeddedArgumentsCannotBeMistakenForProtectedSourceTokens() {
        var plan=prepare("SELECT /*# column*/ FROM [employee]", "{\"column\":{\"type\":\"String\",\"value\":\"__doma_result_opaque_0__\"}}");
        assertTrue(plan.sql().contains("__doma_result_opaque_0__"));
        assertTrue(plan.sql().contains("FROM [employee]"));
    }

    @Test void expressionEvaluationRestoresTheCallersContextClassLoader() throws Exception {
        ClassLoader before=Thread.currentThread().getContextClassLoader();
        try(var projectLoader=new java.net.URLClassLoader(new java.net.URL[0],loader)) {
            var arguments=TemplateArguments.parse("{\"enabled\":{\"type\":\"boolean\",\"value\":true}}",projectLoader);
            SqlInput.prepare("SELECT /*%if enabled*/ 1 /*%else*/ 2 /*%end*/ AS id",SqlInput.Dialect.SQLSERVER,arguments,projectLoader,"");
            assertSame(before,Thread.currentThread().getContextClassLoader());
        }
    }

    private DomaPreparedSelect prepare(String sql,String json){return SqlInput.prepare(sql,SqlInput.Dialect.SQLSERVER,TemplateArguments.parse(json,loader),loader,"");}
    private List<ResultColumn> read(Connection connection,DomaPreparedSelect plan)throws Exception{return new JdbcMetadataReader().read(connection,plan,"",TypeNameMappings.empty(),stage->{},new PreviewExecution.Cancellation());}
    private Connection database()throws Exception{
        Connection connection=DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MSSQLServer");
        try(var setup=connection.createStatement()){setup.execute("CREATE TABLE employee(id BIGINT,name VARCHAR(50))");}return connection;
    }
}
