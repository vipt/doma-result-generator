/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GeneratorTest {
    @TempDir Path directory;

    private Connection database() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID());
        try (var statement = connection.createStatement()) {
            statement.execute("create table department (id integer, name varchar(100))");
            statement.execute("create table employee (id bigint, department_id integer, name varchar(100), salary decimal(10,2), hired_on date)");
            statement.execute("insert into department values (1, 'Development')");
            statement.execute("insert into employee values (11, 1, 'Alice', 1234.56, date '2024-01-02')");
        }
        return connection;
    }

    @Test void joinMetadataUsesResultAliasesAndTypes() throws Exception {
        try (Connection connection = database()) {
            var columns = new JdbcMetadataReader().read(connection, """
                    select e.id as employee_id, e.name as employee_name, d.name as department_name,
                           e.salary, e.hired_on, count(*) over() as total_count
                    from employee e left join department d on d.id = e.department_id
                    where e.id = /* employeeId */11;
                    """);
            assertEquals(List.of(
                    new ResultColumn("EMPLOYEE_ID", "java.lang.Long"),
                    new ResultColumn("EMPLOYEE_NAME", "java.lang.String"),
                    new ResultColumn("DEPARTMENT_NAME", "java.lang.String"),
                    new ResultColumn("SALARY", "java.math.BigDecimal"),
                    new ResultColumn("HIRED_ON", "java.time.LocalDate"),
                    new ResultColumn("TOTAL_COUNT", "java.lang.Long")), columns);
            assertFalse(connection.isClosed());
        }
    }

    @Test void generatedEntityCompilesWithDomaAnnotationProcessorAndMapsActualRows() throws Exception {
        try (Connection connection = database()) {
            String query = "select id as employee_id, name as employee_name, salary, hired_on from employee";
            String entity = new EntityGenerator().generate("example", "EmployeeResult",
                    new JdbcMetadataReader().read(connection, query));
            Path entityFile = directory.resolve("EmployeeResult.java");
            Path daoFile = directory.resolve("EmployeeDao.java");
            Files.writeString(entityFile, entity);
            Files.writeString(daoFile, """
                    package example;
                    @org.seasar.doma.Dao
                    public interface EmployeeDao {
                      @org.seasar.doma.Select
                      @org.seasar.doma.Sql("select id as employee_id, name as employee_name, salary, hired_on from employee")
                      java.util.List<EmployeeResult> selectAll();
                    }
                    """);
            compile(List.of(entityFile, daoFile), true);
            assertTrue(Files.exists(directory.resolve("example/_EmployeeResult.class")));
            try (URLClassLoader loader = new URLClassLoader(new URL[] { directory.toUri().toURL() }, getClass().getClassLoader())) {
                var dataSource = new org.h2.jdbcx.JdbcDataSource();
                dataSource.setURL(connection.getMetaData().getURL());
                org.seasar.doma.jdbc.Config config = new org.seasar.doma.jdbc.Config() {
                    @Override public javax.sql.DataSource getDataSource() { return dataSource; }
                    @Override public org.seasar.doma.jdbc.dialect.Dialect getDialect() {
                        return new org.seasar.doma.jdbc.dialect.H2Dialect();
                    }
                };
                Class<?> daoClass = loader.loadClass("example.EmployeeDaoImpl");
                Object dao = daoClass.getConstructor(org.seasar.doma.jdbc.Config.class).newInstance(config);
                List<?> rows = (List<?>) daoClass.getMethod("selectAll").invoke(dao);
                assertEquals(1, rows.size());
                Object row = rows.get(0);
                assertEquals(11L, row.getClass().getMethod("getEmployeeId").invoke(row));
                assertEquals("Alice", row.getClass().getMethod("getEmployeeName").invoke(row));
                assertEquals(new java.math.BigDecimal("1234.56"), row.getClass().getMethod("getSalary").invoke(row));
                assertEquals(java.time.LocalDate.of(2024, 1, 2), row.getClass().getMethod("getHiredOn").invoke(row));
            }
        }
    }

    @Test void allSupportedTypesPassDomaProcessing() throws Exception {
        var types = List.of("java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
                "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.math.BigDecimal",
                "java.lang.String", "byte[]", "java.time.LocalDate", "java.time.LocalTime",
                "java.time.LocalDateTime", "java.sql.Blob", "java.sql.Clob", "java.sql.NClob", "java.sql.SQLXML");
        var columns = new java.util.ArrayList<ResultColumn>();
        for (int i = 0; i < types.size(); i++) columns.add(new ResultColumn("column_" + i, types.get(i)));
        Path file = directory.resolve("AllTypesResult.java");
        Files.writeString(file, new EntityGenerator().generate("example", "AllTypesResult", columns));
        compile(List.of(file), true);
    }

    @Test void usesPrivateFieldsAndShortNamesForAllGeneratedTypes() throws Exception {
        String source = new EntityGenerator().generate("example", "Result", List.of(
                new ResultColumn("ID", "java.lang.Long"), new ResultColumn("NAME", "java.lang.String"),
                new ResultColumn("AMOUNT", "java.math.BigDecimal"), new ResultColumn("CREATED_AT", "java.time.LocalDateTime"),
                new ResultColumn("CONTENT", "byte[]")));
        for (String type : List.of("org.seasar.doma.Entity", "org.seasar.doma.Column", "java.math.BigDecimal", "java.time.LocalDateTime")) {
            assertTrue(source.contains("import " + type + ";"));
        }
        assertTrue(source.contains("@Entity"));
        assertTrue(source.contains("@Column(name = \"ID\")"));
        assertTrue(source.contains("private Long id;"));
        assertTrue(source.contains("private String name;"));
        assertTrue(source.contains("private BigDecimal amount;"));
        assertTrue(source.contains("private LocalDateTime createdAt;"));
        assertTrue(source.contains("private byte[] content;"));
        assertTrue(source.contains("public BigDecimal getAmount()"));
        assertTrue(source.contains("setCreatedAt(LocalDateTime createdAt)"));
        assertFalse(source.contains("java.lang."));
        Path file = directory.resolve("Result.java");
        Files.writeString(file, source);
        compile(List.of(file), true);
    }

    @Test void handlesTypeNameCollisionsWithoutAmbiguousImports() throws Exception {
        String source = new EntityGenerator().generate("example", "Result", List.of(
                new ResultColumn("SQL_DATE", "java.sql.Date"), new ResultColumn("UTIL_DATE", "java.util.Date")));
        assertTrue(source.contains("import java.sql.Date;"));
        assertFalse(source.contains("import java.util.Date;"));
        assertTrue(source.contains("private Date sqlDate;"));
        assertTrue(source.contains("private java.util.Date utilDate;"));
        Path file = directory.resolve("Result.java");
        Files.writeString(file, source);
        compile(List.of(file), false);
    }

    @Test void entityNameCanCollideWithAnAnnotationName() throws Exception {
        String source = new EntityGenerator().generate("example", "Column", List.of(new ResultColumn("ID", "java.lang.Long")));
        assertTrue(source.contains("@org.seasar.doma.Column(name"));
        assertFalse(source.contains("import org.seasar.doma.Column;"));
        Path file = directory.resolve("Column.java");
        Files.writeString(file, source);
        compile(List.of(file), true);
    }

    @Test void nameRulesGenerateCompilableDateTimeAndDomainPropertiesAndMapActualRows() throws Exception {
        try (Connection connection = database()) {
            var rules = TypeNameMappings.parse(".*Datetime$=java.time.LocalDateTime\nsalaryValue=example.Salary\n");
            String query = "select id, hired_on as hired_datetime, salary as salary_value from employee";
            Path entityFile = directory.resolve("MappedResult.java");
            Path domainFile = directory.resolve("Salary.java");
            Path daoFile = directory.resolve("MappedDao.java");
            Files.writeString(entityFile, new EntityGenerator().generate("example", "MappedResult",
                    new JdbcMetadataReader().read(connection, query, "example.MappedResult", rules)));
            Files.writeString(domainFile, """
                    package example;
                    @org.seasar.doma.Domain(valueType = java.math.BigDecimal.class)
                    public class Salary {
                      private final java.math.BigDecimal value;
                      public Salary(java.math.BigDecimal value) { this.value = value; }
                      public java.math.BigDecimal getValue() { return value; }
                    }
                    """);
            Files.writeString(daoFile, """
                    package example;
                    @org.seasar.doma.Dao
                    public interface MappedDao {
                      @org.seasar.doma.Select
                      @org.seasar.doma.Sql("select id, hired_on as hired_datetime, salary as salary_value from employee")
                      java.util.List<MappedResult> selectAll();
                    }
                    """);
            compile(List.of(entityFile, domainFile, daoFile), true);
            try (URLClassLoader loader = new URLClassLoader(new URL[] { directory.toUri().toURL() }, getClass().getClassLoader())) {
                var dataSource = new org.h2.jdbcx.JdbcDataSource();
                dataSource.setURL(connection.getMetaData().getURL());
                org.seasar.doma.jdbc.Config config = new org.seasar.doma.jdbc.Config() {
                    @Override public javax.sql.DataSource getDataSource() { return dataSource; }
                    @Override public org.seasar.doma.jdbc.dialect.Dialect getDialect() {
                        return new org.seasar.doma.jdbc.dialect.H2Dialect();
                    }
                };
                Class<?> daoClass = loader.loadClass("example.MappedDaoImpl");
                Object dao = daoClass.getConstructor(org.seasar.doma.jdbc.Config.class).newInstance(config);
                List<?> rows = (List<?>) daoClass.getMethod("selectAll").invoke(dao);
                assertEquals(1, rows.size());
                Object row = rows.get(0);
                assertEquals(11L, row.getClass().getMethod("getId").invoke(row));
                assertEquals(java.time.LocalDateTime.of(2024, 1, 2, 0, 0), row.getClass().getMethod("getHiredDatetime").invoke(row));
                Object salary = row.getClass().getMethod("getSalaryValue").invoke(row);
                assertEquals(new java.math.BigDecimal("1234.56"), salary.getClass().getMethod("getValue").invoke(salary));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "quote\"label", "back\\slash", "new\nline", "control\tlabel" })
    void rejectsLabelsThatDomaProcessorCannotEscape(String label) {
        assertThrows(IllegalArgumentException.class, () -> new ResultColumn(label, "java.lang.String"));
    }

    @Test void invalidAndCollidingLabelsProduceCompilableDistinctAccessors() throws Exception {
        var columns = List.of("USER_ID", "user-id", "CLASS", "123", "???", "name", "Name", "日本語").stream()
                .map(label -> new ResultColumn(label, "java.lang.String")).toList();
        // Case-only labels cannot map to separate Doma fields.
        assertThrows(IllegalArgumentException.class, () -> new EntityGenerator().generate("example", "Result", columns));
        var valid = columns.stream().filter(column -> !column.label().equals("Name")).toList();
        String source = new EntityGenerator().generate("example", "Result", valid);
        assertTrue(source.contains("getUserId2()"));
        assertTrue(source.contains("getClassValue()"));
        Path file = directory.resolve("Result.java");
        Files.writeString(file, source);
        compile(List.of(file), true);
    }

    @Test void rejectsUnsupportedDatabaseTypes() throws Exception {
        try (Connection connection = database()) {
            var error = assertThrows(MetadataException.class, () -> new JdbcMetadataReader().read(connection,
                    "select ARRAY[1,2] as external_ids"));
            assertTrue(error.getMessage().contains("EXTERNAL_IDS"));
        }
    }

    @Test void describesEmptyResults() throws Exception {
        try (Connection connection = database()) {
            assertEquals("java.lang.Long", new JdbcMetadataReader().read(connection,
                    "select id from employee where 1 = 0").get(0).javaType());
        }
    }

    @Test void tinyintUsesShortLikeTheOfficialGenerator() throws Exception {
        try (Connection connection = database()) {
            assertEquals("java.lang.Short", new JdbcMetadataReader().read(connection,
                    "select cast(127 as tinyint) as small_value").get(0).javaType());
        }
    }

    @Test void driverLoadsFromExternalJarAndClosesIndependently() throws Exception {
        Path jar = Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JdbcDriverLoader loader = new JdbcDriverLoader(List.of(jar));
                Connection connection = loader.connect("org.h2.Driver", "jdbc:h2:mem:isolated", "sa", "")) {
            assertEquals("java.lang.Integer", new JdbcMetadataReader().read(connection, "select 1 as sample_value").get(0).javaType());
            assertSame(loader, Class.forName("org.h2.Driver", false, loader).getClassLoader());
        }
        assertNotNull(DriverManager.getDriver("jdbc:h2:mem:stillAvailable"));
    }

    @Test void metadataLookupDoesNotExecuteSelect() throws Exception {
        try (Connection connection = database()) {
            try (var statement = connection.createStatement()) { statement.execute("create sequence sample_sequence start with 1"); }
            new JdbcMetadataReader().read(connection, "select next value for sample_sequence as sequence_value");
            try (var statement = connection.createStatement(); var result = statement.executeQuery("select next value for sample_sequence")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "select 1", "-- comment\nselect 'delete;?' as name; -- end",
            "select /* employeeId */1", "with example as (select 1 as id) select id from example",
            "select $$delete;?$$ as body", "select $tag$/*%if*/$tag$ as body",
            "select [delete], `update`, \"drop\" from example", "/* outer /* inner */ end */ select 1" })
    void acceptsConcreteSelects(String sql) { assertNotNull(SqlInput.validate(sql)); }

    @ParameterizedTest
    @ValueSource(strings = { "delete from employee", "select 1; delete from employee", "select ?",
            "select /*%expand alias*/* from employee", "select 1 /*%if enabled*/ where id = 1 /*%end*/",
            "select /*# order */1", "select /*^ name */'name'", "select 'unclosed", "select /* broken",
            "with x as (delete from employee returning *) select * from x", "select id into example from employee", "" })
    void rejectsUnresolvedOrNonSelectStatements(String sql) {
        assertThrows(IllegalArgumentException.class, () -> SqlInput.validate(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = { "class", "foo-bar", "_", "", "record", "x.y", "String", "Object", "Class" })
    void rejectsInvalidClassNames(String value) {
        assertThrows(IllegalArgumentException.class, () -> JavaNames.validateTarget("example", value));
    }

    private void compile(List<Path> sources, boolean domaProcessing) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Run tests with a JDK.");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var options = new java.util.ArrayList<>(List.of("--release", "8", "-classpath", System.getProperty("java.class.path"),
                    "-d", directory.toString(), "-s", directory.toString()));
            if (domaProcessing) options.addAll(List.of("-processor",
                    "org.seasar.doma.internal.apt.processor.DomainProcessor,org.seasar.doma.internal.apt.processor.EntityProcessor,org.seasar.doma.internal.apt.processor.DaoProcessor"));
            else options.add("-proc:none");
            Boolean success = compiler.getTask(null, manager, diagnostics, options, null,
                    manager.getJavaFileObjectsFromPaths(sources)).call();
            assertEquals(Boolean.TRUE, success, diagnostics.getDiagnostics().toString());
        }
    }
}
