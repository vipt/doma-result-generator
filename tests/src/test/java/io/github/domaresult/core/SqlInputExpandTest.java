/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SqlInputExpandTest {
    static Stream<Arguments> expansions() {
        return Stream.of(
                Arguments.of("select /*%expand*/* from employee", "select * from employee"),
                Arguments.of("SELECT /*%expand */ * FROM EMPLOYEE", "SELECT * FROM EMPLOYEE"),
                Arguments.of("select /*%expand*/\n  * from employee; -- end", "select * from employee -- end"),
                Arguments.of("select /*%expand \"e\" */* from employee e", "select e.* from employee e"),
                Arguments.of("select /*%expand \"\"*/* from employee", "select * from employee"),
                Arguments.of("select /*%expand \"\\\"E\\\"\" */* from employee \"E\"", "select \"E\".* from employee \"E\""),
                Arguments.of("select /*%expand \"[employee alias]\" */* from employee [employee alias]", "select [employee alias].* from employee [employee alias]"),
                Arguments.of("select /*%expand \"`employee alias`\" */* from employee `employee alias`", "select `employee alias`.* from employee `employee alias`"),
                Arguments.of("select /*%expand \"schema.employee\" */* from schema.employee", "select schema.employee.* from schema.employee"),
                Arguments.of("with data as (select /*%expand*/* from employee) select /*%expand \"d\"*/* from data d", "with data as (select * from employee) select d.* from data d"));
    }

    @ParameterizedTest @MethodSource("expansions")
    void preparesExpansionSqlAndIsIdempotent(String template, String expected) {
        assertEquals(expected, SqlInput.validate(template));
        assertEquals(expected, SqlInput.validate(SqlInput.validate(template)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select '/*%expand*/*' as text_value from employee",
            "select $$/*%expand \"e\" */*$$ as text_value from employee",
            "select \"/*%expand*/*\" from employee",
            "select id from employee -- /*%expand*/*\n",
            "select /* ordinary /*%expand*/* nested comment */ id from employee"
    })
    void keepsTextAndOrdinaryCommentsUnchanged(String sql) {
        assertEquals(sql, SqlInput.validate(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select /*%expand*/ id from employee",
            "select /*%expand*/",
            "select /*%expand alias */* from employee e",
            "select /*%expand \"e\" + \"x\" */* from employee e",
            "select /*%expand \"e; drop table employee\" */* from employee e",
            "select /*%expand \"e -- comment\" */* from employee e",
            "select /*%expand \"e)\" */* from employee e",
            "select 1; /*%expand*/*",
            "select /*%expandx*/* from employee",
            "select /*%if enabled*/ /*%expand*/* from employee /*%end*/"
    })
    void rejectsUnresolvedMalformedOrMultipleStatements(String sql) {
        assertThrows(GenerationException.class, () -> SqlInput.validate(sql));
    }

    @Test void readsOracleStyleExpansionAndInListUsingTestLiteralsEvenWithNoMatchingRows() throws Exception {
        String template = """
                SELECT /*%expand*/*
                  FROM EMPLOYEE
                 WHERE DEPARTMENT_ID IN /* departmentIdsList */(0)
                """;
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=Oracle")) {
            try (var statement = connection.createStatement()) {
                statement.execute("create table EMPLOYEE (ID bigint, DEPARTMENT_ID integer, NAME varchar(100))");
                statement.execute("insert into EMPLOYEE values (1, 10, 'Alice')");
            }
            assertEquals(List.of(new ResultColumn("ID", "java.lang.Long"),
                    new ResultColumn("DEPARTMENT_ID", "java.lang.Integer"),
                    new ResultColumn("NAME", "java.lang.String")), new JdbcMetadataReader().read(connection, template));
            assertTrue(SqlInput.validate(template).contains("IN /* departmentIdsList */(0)"));
        }
    }

    @Test void expansionWithAliasDescribesOnlyThatTableInAJoin() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID())) {
            try (var statement = connection.createStatement()) {
                statement.execute("create table employee (id bigint, name varchar(100))");
                statement.execute("create table department (id integer, name varchar(100))");
            }
            String sql = "select /*%expand \"e\" */* from employee e left join department d on e.id = d.id";
            assertEquals(List.of(new ResultColumn("ID", "java.lang.Long"), new ResultColumn("NAME", "java.lang.String")),
                    new JdbcMetadataReader().read(connection, sql));
        }
    }
}
