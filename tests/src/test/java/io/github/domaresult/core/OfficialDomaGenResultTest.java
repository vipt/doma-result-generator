/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.seasar.doma.extension.gen.ResultSetMetaReader;
import org.seasar.doma.extension.gen.dialect.StandardGenDialect;
import javax.sql.DataSource;

class OfficialDomaGenResultTest {
    @Test void executesTheSqlThroughTheUnmodifiedOfficialReaderWithoutIteratingResultRows() throws Exception {
        String sql="SELECT id, name FROM employee WHERE id=/*id*/1";
        int[] calls={0,0,0};
        try(var real=database()) {
            Connection guarded=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                assertEquals("prepareStatement",m.getName());assertEquals(sql,a[0]);
                PreparedStatement statement=real.prepareStatement((String)a[0]);
                return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(s,c,v)->{
                    if(c.getName().equals("getMetaData"))fail("Use ResultSet metadata, as doma-gen does");
                    if(c.getName().equals("executeQuery")) {
                        assertTrue(Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame->frame.getClassName().equals(ResultSetMetaReader.class.getName())));
                        calls[0]++;ResultSet rs=statement.executeQuery();
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(r,operation,args)->{
                            if(operation.getName().equals("next"))fail("The official reader needs no row iteration");
                            if(operation.getName().equals("close"))calls[1]++;
                            return invoke(operation,rs,args);
                        });
                    }
                    if(c.getName().equals("close"))calls[2]++;
                    return invoke(c,statement,v);
                });
            });
            assertEquals(List.of(new ResultColumn("ID","java.lang.Long"),new ResultColumn("NAME","java.lang.String")),new JdbcMetadataReader().read(guarded,sql));
            assertArrayEquals(new int[]{1,1,1},calls);assertFalse(real.isClosed());
        }
    }

    @Test void domaCommentsAndTestLiteralsPassDirectlyToJdbcWithoutDaoArguments() throws Exception {
        String sql="""
                SELECT DISTINCT id, name FROM employee
                WHERE 1=1
                /*%if ids.size()>0*/ AND (
                  /*%for item:ids*/ id=/*item*/1
                  /*%if item_has_next*/ /*# "OR" */ /*%end*/
                  /*%end*/)
                /*%end*/
                """;
        try(var connection=database()) {
            assertEquals(sql,SqlInput.validate(sql));
            var columns=new JdbcMetadataReader().read(connection,sql);
            assertEquals(2,columns.size());
            assertTrue(new EntityGenerator().generate("example.result","EmployeeResult",columns).contains("private String name;"));
        }
    }

    @Test void aBindLookingCommentBeforeAColumnIsNotParsedAsADomaRuntimeExpression() throws Exception {
        try(var connection=database()) {
            String sql="SELECT /* columnName */id FROM employee WHERE id=/*id*/1";
            assertEquals(List.of(new ResultColumn("ID","java.lang.Long")),new JdbcMetadataReader().read(connection,sql));
        }
    }

    @Test void conditionalCommentsDoNotSelectBranchesOrLoadApplicationClasses() throws Exception {
        String sql="SELECT id /*%if @missing.Class@enabled()*/, name /*%else*/, 'x' AS label /*%end*/ FROM employee";
        try(var connection=database()) {
            assertEquals(3,new JdbcMetadataReader().read(connection,sql).size());
        }
    }

    @Test void expansionCommentsRemainCommentsAndJdbcDescribesTheActualStar() throws Exception {
        try(var connection=database()) {
            String sql="SELECT /*%expand unknownAlias*/* FROM employee";
            assertEquals(sql,SqlInput.validate(sql));
            assertEquals(2,new JdbcMetadataReader().read(connection,sql).size());
        }
    }

    @Test void columnTypesMatchTheOfficialGeneratorMapping() throws Exception {
        try(var connection=database()) {
            DataSource source=(DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
                if(m.getName().equals("getConnection"))return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(c,method,args)->method.getName().equals("close")?null:invoke(method,connection,args));
                throw new UnsupportedOperationException();
            });
            var dialect=new StandardGenDialect();
            var table=new ResultSetMetaReader(dialect,source).read("SELECT id,name FROM employee");
            var actual=new JdbcMetadataReader().read(connection,"SELECT id,name FROM employee");
            for(int i=0;i<actual.size();i++)assertEquals(dialect.getMappedPropertyClassName(table.getColumnMetas().get(i)),actual.get(i).javaType());
        }
    }

    @Test void jdbcErrorCodesAndTheCallerConnectionArePreservedOnExecutionFailure() throws Exception {
        SQLException expected=new SQLException("private details","S0001",156);
        int[] closed={0};
        PreparedStatement statement=(PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
            if(m.getName().equals("executeQuery"))throw expected;
            if(m.getName().equals("close")){closed[0]++;return null;}
            throw new UnsupportedOperationException(m.getName());
        });
        Connection connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            assertEquals("prepareStatement",m.getName());return statement;
        });
        assertSame(expected,assertThrows(SQLException.class,()->new JdbcMetadataReader().read(connection,"SELECT 1")));
        assertEquals(1,closed[0]);
    }

    @ParameterizedTest @ValueSource(strings={"SELECT 1; DELETE FROM employee","WITH x AS (DELETE FROM employee) SELECT * FROM x","DELETE FROM employee","SELECT 1 INTO employee"})
    void rejectsWritesAndAdditionalQueriesBeforeConnectingToTheOfficialReader(String sql) {
        Connection connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{throw new AssertionError("No JDBC call for invalid input");});
        assertThrows(GenerationException.class,()->new JdbcMetadataReader().read(connection,sql));
    }

    @Test void rawCtesAndTrailingTerminatorsNeedNoMetadataWrapperPreamble() {
        String cte="WITH x AS (SELECT 1 AS id) SELECT id FROM x";
        assertEquals(cte,SqlInput.forMetadata(cte,SqlInput.Dialect.SQLSERVER));
        assertEquals("SELECT 1",SqlInput.validate("SELECT 1;;"));
    }
    private static Object invoke(java.lang.reflect.Method method,Object target,Object[] args)throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}
    }
    private static Connection database()throws SQLException {
        Connection connection=DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID());
        try(var setup=connection.createStatement()){
            setup.execute("CREATE TABLE employee(id BIGINT,name VARCHAR(50))");
            setup.execute("INSERT INTO employee VALUES(1,'sample')");
        }
        return connection;
    }
}
