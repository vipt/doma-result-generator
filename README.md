# Doma Result Generator

[Install / update site](https://vipt.github.io/doma-result-generator/) · [日本語](README.ja.md) · [Contributing](CONTRIBUTING.md) · [Apache-2.0](LICENSE)

An independent Eclipse plug-in that generates a Doma 2 entity from the columns returned by a SELECT. Designed for joins, projections, aggregates and SQL result sets that do not match a single table.

It also opens a DAO's external SQL file directly from a Java method call.

**Status: initial prototype.** The core is exercised with H2 and Doma 2.57.0. Support for another database depends on its JDBC driver's pre-execution metadata support; it is not a claim of compatibility with every database. This is not an official Doma or Eclipse project.

## Workflow

1. Save a `.sql` file in an Eclipse Java project.
2. In Package Explorer or Project Explorer, right-click the file and select **Generate Doma Result Class...**.
3. Select your JDBC driver JAR(s), and enter the driver class, URL and credentials. Drivers are supplied separately, so vendor libraries are not redistributed.
4. Select a Java source folder, package and class name. Optionally select a UTF-8 type mapping file; `name.properties` in the project root is initially selected when present.
5. Review the **Metadata SQL** tab. It starts with the saved SQL file. Changes made here are used for metadata lookup and do not change the SQL file.
6. Select **Preview** and review **Proposed Java**. For a new class, select **Generate**. For an existing class, review the comparison and select **Update**.

The plug-in uses `Connection.prepareStatement(...).getMetaData()`. It does not call `execute`, `executeQuery` or fetch result rows. Some drivers may communicate with the database while preparing/describing a statement. No automatic execution fallback is performed when metadata is unavailable.

Connection settings are kept only in the dialog and are not persisted. Raw driver exceptions are not logged or displayed, since they may include connection details. SQLState and exception class are displayed for driver errors; generator errors give actionable explanations. Use your normal trusted driver and database connection settings. Driver-specific timeout and SSL settings can be supplied in the JDBC URL according to the driver's documentation.

## Example

```sql
select
    e.id as employee_id,
    e.name as employee_name,
    d.name as department_name,
    e.salary
from employee e
left join department d on d.id = e.department_id
where e.id = /* employeeId */11
```

For a JDBC driver that reports those labels in upper case, generation produces:

```java
package com.example.dto;

@org.seasar.doma.Entity
public class EmployeeResult {
    /** @domaGenerated */
    @org.seasar.doma.Column(name = "EMPLOYEE_ID")
    java.lang.Long employeeId;

    @org.seasar.doma.Column(name = "EMPLOYEE_NAME")
    java.lang.String employeeName;

    // Other fields and public getters/setters are generated as well.
}
```

Reference the generated class from an existing Doma DAO:

```java
@Select
List<EmployeeResult> selectEmployees(Long employeeId);
```

The target project must have Doma configured, including annotation processing. This plug-in creates or updates the result entity; it does not change DAO signatures or project dependencies.

## Updating an existing class

Select the existing class's source folder, package and name, then press **Preview**. The plug-in merges the new SELECT result definition into the saved Java source and opens an Eclipse comparison: **Current saved class** on the left, **Proposed update** on the right. Both sides are read-only. Close the comparison and select **Update** to apply the reviewed result. **Compare changes...** reopens the comparison; **Proposed Java** shows the complete merged source. Closing the comparison or canceling the generator writes nothing. With no changes, Update stays disabled.

Generated fields and accessors carry the Javadoc ownership tag `@domaGenerated`. The updater uses JDT syntax nodes and source ranges to make targeted changes:

- New result columns add fields and accessors. Type changes update the field and simple getter/setter signatures. `name.properties` rules apply to these updates too.
- Handwritten methods, constructors, fields, imports, class annotations, nested types and other top-level types are retained. Customized accessor bodies are retained when their signatures remain compatible.
- Javadoc, line comments and block comments are kept. When an obsolete generated declaration can be removed, its user comments remain as standalone comments. Only generator ownership tags are removed with the obsolete declaration.
- A marked obsolete field and its simple marked accessors can be removed. If retained code refers to that field, an accessor has a custom body, or the field has custom initializers/annotations, the update is stopped with an explanation.
- A handwritten member collision or an incompatible customized accessor stops the update. A private JDT working copy also checks the proposed class using the target project's Java build path. Java errors, including type errors in handwritten code, must be resolved before applying the update. The private validation does not save the class.

Save existing edits before previewing. At Update, the saved source and modification stamp must still match the preview; dirty editors, changed files and deleted files stop the update. The file is written in its existing character encoding, with Eclipse local-history preservation enabled. Local-history retention follows workspace settings. The comparison covers the target Java file; callers in other files may still need changes when its public API changes.

### Classes from the earlier prototype

For a class with the original `Generated from SELECT result metadata.` header and no ownership tags, the first update can adopt matching simple `@Column(name = ...)` fields and ordinary accessors. Their user comments and custom methods are retained, and ownership tags are added to adopted members. Unmarked old fields absent from the current SQL remain in place, with a preview note, because their ownership cannot be established. Review those fields manually. Arbitrary unmarked field collisions are not overwritten. Immutable entities and unsupported/custom declarations require manual adjustment.

## Jump from a DAO call to SQL

In the Java editor, place the cursor on `selectById` in a call such as:

```java
Employee employee = employeeDao.selectById(id);
```

Right-click and select **Jump to Doma SQL**. If the method is declared in `com.example.dao.EmployeeDao`, annotated with `@org.seasar.doma.Dao`, the command opens `META-INF/com/example/dao/EmployeeDao/selectById.sql` under a source folder on that DAO's build path. It also works on DAO method declarations and across referenced workspace Java projects.

- Dialect variants such as `selectById-postgres.sql` are included. Multiple matches appear in a picker showing their workspace paths.
- `@Script` methods open `.script` files. Inline `@Sql` methods are skipped.
- A missing file displays an explanation. Navigation does not create files or connect to a database.
- Assign a shortcut under **Preferences → General → Keys → Jump to Doma SQL**.

This feature searches workspace source folders on the DAO's Java build path. SQL files inside JARs and tracing through arbitrary service wrappers are outside its scope. It can coexist with [Doma Tools](https://marketplace.eclipse.org/content/doma-tools); no changes to Doma Tools itself are required.

## SQL templates

Simple Doma bind comments with executable test literals, such as `/* employeeId */11`, can remain. JDBC parameter placeholders (`?`) must be replaced with typed sample literals.

This version does not evaluate `/*%if*/`, `/*%for*/`, `/*%expand*/`, embedded variables (`/*# ... */`) or literal variables (`/*^ ... */`). Replace these with a concrete SELECT in **Metadata SQL**. For example, replace `select /*%expand*/* from employee` with `select * from employee`. If a condition changes the projected columns, select the intended projection explicitly. The original template is preserved.

SELECTs, joins, aggregates and `WITH ... SELECT` are accepted. Multiple statements, data modifying CTEs and `SELECT ... INTO` are rejected. Validation is intentionally conservative; quote identifiers that match reserved data modification words. It is input validation, not a SQL security sandbox.

## Type mapping

| JDBC type | Generated Java type |
| --- | --- |
| BOOLEAN, BIT | Boolean |
| TINYINT, SMALLINT | Short |
| INTEGER, BIGINT | Integer, Long |
| REAL | Float |
| FLOAT, DOUBLE | Double |
| NUMERIC, DECIMAL | BigDecimal |
| CHAR/VARCHAR and national/long variants | String |
| BINARY/VARBINARY/LONGVARBINARY | byte[] |
| DATE, TIME, TIMESTAMP | LocalDate, LocalTime, LocalDateTime |
| BLOB, CLOB, NCLOB | Blob, Clob, NClob |
| SQLXML | SQLXML |

Numeric/boolean fields use wrappers so SQL NULLs, including outer join results, can be represented. The JDBC driver determines the SQL type of expressions. Without a matching name rule, unsupported JDBC types, including ARRAY, OTHER and time zone types, are rejected rather than silently mapped to an invalid Doma property. Vendor types such as UUID and JSON need either a supported JDBC representation, an explicit type mapping, or an appropriate SQL CAST. Multi-bit BIT types and vendor-specific temporal/unsigned number semantics may require mappings or CASTs.

Column labels become camel case field names. `@Column` retains each original label. Invalid identifier characters, Java keywords and accessor collisions are handled. Case-insensitive duplicate result labels must be disambiguated with SQL aliases. Labels containing double quotes, backslashes or control characters require a simple AS alias because the tested Doma 2 annotation processor does not escape them safely in its own generated source. Existing classes are updated through the reviewed merge workflow above.

## Name-based type overrides (`name.properties`)

Select a file in **Configuration → Type mappings (optional)**. A project-root `name.properties` is initially selected when it exists. The file is reloaded each time **Preview** is pressed; save edits before previewing. Clear the field to use JDBC mapping alone. Any filename can be selected.

The Doma-Gen-style file is UTF-8 and contains ordered `property-name-regex=JavaType` rules:

```properties
.*Datetime$=java.time.LocalDateTime
```

For example, `CREATED_DATETIME` becomes the Java property `createdDatetime` and is generated as `java.time.LocalDateTime`, even when JDBC's default mapping would be `LocalDate`:

```java
@org.seasar.doma.Column(name = "CREATED_DATETIME")
java.time.LocalDateTime createdDatetime;
```

- Rules match the **final Java property name**, including any collision suffix. They do not match the raw SQL column label. `@Column` retains that label.
- Matching is case-sensitive and uses Java regex `Matcher.matches()` (the whole property name). `createdDateTime` does not match `.*Datetime$`.
- The **first matching rule from top to bottom** wins, and takes precedence over JDBC mapping. Unmatched properties keep their JDBC types. An explicit rule can supply a type even when the JDBC type is otherwise unsupported.
- Scope a rule to a class with `com.example.dto.EmployeeResult@.*Datetime$=java.time.LocalDateTime`. The class-name prefix is literal; the property part is a regex. Place a scoped rule before broader rules that could also match.
- Regex capture replacements such as `$1` and the leading-`@` property-pattern convention from Doma-Gen are supported. For example, `@.*(Date)time$=java.time.Local$1Time`.
- Blank lines and lines starting with `#` or `!` are ignored. Surrounding whitespace is trimmed. The first `=` separates pattern and replacement. Backslashes in regexes are preserved; Java `Properties` unescaping and multiline continuations are not used.
- Values may be fully qualified Java class names (including Doma domains), primitives, or `byte[]`. Generic type expressions and other array expressions are not supported. Malformed rules and invalid expanded type names report a line number.

For example, `employeeId=com.example.domain.EmployeeId` can refer to a Doma `@Domain` class in the target project. The generator checks type-name syntax; class existence, Doma property compatibility and JDBC value conversion must be valid in that project. A name rule specifies the property type and does not add a new database value converter. Prefer wrapper types when NULL is possible.

See [examples/name.properties](examples/name.properties) and the official [EntityPropertyClassNameResolver](https://github.com/domaframework/doma-gen/blob/master/src/main/java/org/seasar/doma/extension/gen/EntityPropertyClassNameResolver.java).

## Requirements and installation

- Eclipse IDE with Java Development Tools, targeting Eclipse **2023-12 / 4.30** or later; runtime Java **17+**.
- A JDBC driver that can return `PreparedStatement.getMetaData()` before execution, and database access.
- A Java project using Doma 2. Generated classes use Java 8 language features and Java 8 date/time types.
- To build: JDK 17+. The included Apache Maven Wrapper pins Maven 3.8.6 for the Tycho 3 build; use `mvnw.cmd` on Windows.

In Eclipse, open **Help → Install New Software... → Add...** and enter this update site URL:

```text
https://vipt.github.io/doma-result-generator/
```

Select **Doma Result Generator**, review the installation details and license, and restart Eclipse. Future updates are available through **Help → Check for Updates**. The [installation page](https://vipt.github.io/doma-result-generator/) also offers a ZIP download.

Build the plug-in and p2 update site:

```sh
./mvnw --batch-mode --no-transfer-progress verify
```

The installable ZIP is `repository/target/doma-result-generator-repository-0.1.0-SNAPSHOT.zip`. In Eclipse select **Help → Install New Software... → Add... → Archive...**, select that ZIP, select **Doma Result Generator**, complete installation and restart. This build fetches its target platform from the pinned Eclipse 2023-12 repository.

For development with Eclipse PDE, import `plugin` as an existing project, select a Java 17-compatible Eclipse target platform and launch **Run As → Eclipse Application**. The Maven build is the authoritative packaging path.

Run portable core tests without resolving the Eclipse target platform:

```sh
./mvnw -f tests/pom.xml test
```

Tests cover join/aggregate metadata, empty results, no query execution, driver isolation, names/aliases, template validation, ordered name rules and Java 8 compilation with the real Doma annotation processor. Integration tests compile generated entities and DAOs, then read actual H2 rows through Doma, including an overridden LocalDateTime property and a custom domain.

The complete build also runs `navigation-tests` in a headless Eclipse runtime, using real JDT Java projects to check symbol resolution, unsaved working copies, referenced projects, dialect variants and exclusion of unrelated methods and build output copies.

## Relationship to the official generator

[doma-gen](https://github.com/domaframework/doma-gen) is archived and directs users to [doma-codegen-plugin](https://github.com/domaframework/doma-codegen-plugin). Its compatibility matrix identifies CodeGen Plugin **2.x** as the Doma 2 line. The official tools already support generation from SQL results: see [ResultSetMetaReader](https://github.com/domaframework/doma-gen/blob/master/src/main/java/org/seasar/doma/extension/gen/ResultSetMetaReader.java) and the successor's [CodeGenDtoTask](https://github.com/domaframework/doma-codegen-plugin/blob/2.x/codegen/src/main/java/org/seasar/doma/gradle/codegen/task/CodeGenDtoTask.java).

This prototype adds an Eclipse workflow with JDBC configuration, SQL selection, preview and Java-project integration. The core is implemented independently and has no Gradle/Ant runtime dependency. The official standard mapping was reviewed; TINYINT → Short and SQLXML support reflect that comparison. FLOAT → Double is an intentional conservative choice here. The official `ResultSetMetaReader` executes the SELECT; this prototype obtains prepared-statement metadata before execution and reports an error if that capability is unavailable. No official source code is bundled or copied into the plug-in. A future optional backend could reuse the official generator after addressing packaging and execution behavior.

## Open-source publication

Source is hosted at [vipt/doma-result-generator](https://github.com/vipt/doma-result-generator), with the [Eclipse update site on GitHub Pages](https://vipt.github.io/doma-result-generator/). The Pages root serves both installation instructions and p2 metadata. It is an initial preview; consult the manual acceptance checklist in [CONTRIBUTING.md](CONTRIBUTING.md) and validate database/driver combinations before making support claims.

The **Build and deploy Pages** workflow builds and tests each push to `main`, assembles the landing page and p2 site with `python3 scripts/build_pages.py`, then deploys only a successful build. Pull requests are tested without publishing. GitHub Actions must be selected as the repository's Pages source. The workflow also retains test reports and the update-site ZIP as build artifacts; GitHub Releases are managed separately.

To preview the assembled site locally after `./mvnw verify`:

```sh
python3 scripts/build_pages.py
python3 -m http.server 18765 --directory _site
```

Open `http://localhost:18765/`. `_site` is generated and ignored by Git.

## References

- [Doma 2 entities](https://docs.domaframework.org/en/2.62.1/entity/)
- [Doma 2 SELECT result types](https://docs.domaframework.org/en/2.62.1/query/select/)
- [JDBC PreparedStatement metadata](https://docs.oracle.com/en/java/javase/17/docs/api/java.sql/java/sql/PreparedStatement.html#getMetaData())
- [Eclipse menu contributions](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/extension-points/org_eclipse_ui_menus.html)

## License

[Apache License 2.0](LICENSE). See [NOTICE](NOTICE). Generated Java source is not given a license header by this tool; apply your project's licensing policy to generated files.
