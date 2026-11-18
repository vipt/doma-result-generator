# Doma Result Generator

[Install / update site](https://vipt.github.io/doma-result-generator/) · [日本語](README.ja.md) · [Contributing](CONTRIBUTING.md) · [Apache-2.0](LICENSE)

An independent Eclipse plug-in that generates a Doma 2 entity from the columns returned by a SELECT. Designed for joins, projections, aggregates and SQL result sets that do not match a single table.

**Status: initial prototype.** The core is exercised with H2 and Doma 2.57.0. Support for another database depends on its JDBC driver's pre-execution metadata support; it is not a claim of compatibility with every database. This is not an official Doma or Eclipse project.

## Workflow

1. Save a `.sql` file in an Eclipse Java project.
2. Right-click the SQL file in Package Explorer or Project Explorer, or inside its open SQL editor, and select **Generate Doma Result Class...**.
3. Select a saved **Connection profile**, or select your JDBC driver JAR(s) and enter the driver class, URL and credentials. Drivers are supplied separately, so vendor libraries are not redistributed.
4. Select a Java source folder, package and class name. Optionally select a UTF-8 type mapping file; `name.properties` in the project root is initially selected when present.
5. Review the **Metadata SQL** tab. It starts with the saved SQL file. Changes made here are used for metadata lookup and do not change the SQL file.
6. Select **Preview** and review **Proposed Java**. For a new class, select **Generate**. For an existing class, review the comparison and select **Update**.

The plug-in uses `Connection.prepareStatement(...).getMetaData()`. It does not call `execute`, `executeQuery` or fetch result rows. Some drivers may communicate with the database while preparing/describing a statement. No automatic execution fallback is performed when metadata is unavailable.

Generated fields are private, with public getters/setters. Annotations and property types use imports and short names, including `@Entity`, `@Column`, `BigDecimal` and `LocalDateTime`. Types in `java.lang` or the entity's own package do not need imports. If a short name conflicts with another type or retained handwritten code, that reference stays qualified.

Connection profiles are shared across projects in the same workspace. The last selected profile and Package are remembered per project, including on **Cancel** and after restarting Eclipse. With **Unregistered (project settings)**, driver JAR paths, driver class, JDBC URL and username are remembered per project as before. Package defaults to the exact previously entered text, or `com.example.dto` on first use. These settings are not written into project files. Source folder, class name and edited Metadata SQL are not remembered.

From a SQL editor, the generator reads the current Eclipse text buffer, including unsaved edits, without saving the file. An editor whose unsaved content cannot be read asks you to save first. From Explorer, it reads the saved SQL file.

SQL extensions are case-insensitive in both the menu and default class name: `selectAll.SQL` defaults to `SelectAllResult`.

**Save password in Eclipse Secure Storage** is off by default. Select it to save the Password field using [Eclipse encrypted secure preferences](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/secure_storage_architecture.htm); Eclipse may ask to unlock its storage. To remove the saved password, clear the checkbox or Password field, then select **Update profile** for a profile or close the dialog for unregistered settings. Passwords are never saved in ordinary workspace preferences. Changing the driver class, URL or username clears the restored password. Use the separate Username and Password fields: URLs with embedded credentials, such as Oracle `user/password@...`, query `password=...`, or URL user info, are not remembered. If Secure Storage cannot be read or updated, the dialog reports it without falling back to plaintext.

Raw driver exceptions are not logged or displayed, since they may include connection details. Driver errors show the operation stage, SQLState, vendor error code and Oracle ORA number when available. **Error details...** shows these codes and phase-specific guidance; generator errors give actionable explanations. Use your normal trusted driver and database connection settings. Driver-specific timeout and SSL settings can be supplied in the JDBC URL according to the driver's documentation.

### Registering and sharing connection profiles

1. Open the generator in a project with existing saved settings. **Connection profile → Unregistered (project settings)** shows its previous connection. An existing password is restored when Secure Storage can read it.
2. Select **Save as...** and name the connection, for example Development or QA. This registers the displayed driver JAR paths, driver class, JDBC URL, username, Preview timeout and password-saving choice without re-entering them.
3. In another project, select that name under **Connection profile** to fill the connection settings. Each project restores its last selected profile on subsequent openings.

Select **Update profile** to save shared connection edits. Other projects using it receive the changes the next time they open the generator. Use **Save as...** to create a separate connection. Closing the generator does not overwrite shared profiles. Package, class name, output location, type mapping file and Metadata SQL are not shared.

**Manage...** offers rename and delete. Renaming preserves project selections. Deleting removes the profile's own saved password and projects using it return to their unregistered settings. The original project's unregistered settings are retained.

Use **Export all... / Import...** in the manager to move profiles to another workspace. The UTF-8 properties file includes URLs, usernames and local JAR paths, and excludes passwords. Re-enter passwords and check driver paths after importing. Name collisions receive a numbered suffix; existing profiles are not overwritten.

### Canceling a preview and limiting the wait

During **Preview**, select **Cancel** in the progress dialog to stop waiting and return to the generator, even when the JDBC driver has not returned from connecting. No Java files are changed. Adjust the settings and select Preview again.

**Configuration → Preview timeout (seconds)** limits the entire preview, including connecting, reading metadata, generating the class and preparing an existing-class update. It defaults to **30 seconds** and accepts 1–600 seconds. Save it with Update profile for a shared profile; unregistered settings remember it per project. For Oracle drivers, the same duration also sets the connection and socket-read timeouts. Configure other drivers' own timeouts through their JDBC URL as documented by the vendor.

Cancellation and timeout attempt to interrupt the work, cancel its statement and abort an acquired connection. A driver that ignores these requests may continue in the background; late results are discarded and connections are released when control returns. At most two unfinished previews are allowed. If both remain stuck, another Preview reports that the earlier attempts must finish first.

### macOS: password saving fails

If saving shows `Connection settings or the saved password could not be updated.`, Eclipse may be unable to retrieve the Secure Storage master password from the macOS keychain. This can fail without displaying a password prompt or keychain permission dialog. If the Eclipse log contains an `OSXProvider` error, such as `Could not obtain password. Result: -25300`, try switching to **UI Prompt**:

1. Open Eclipse settings/preferences, usually with `⌘,` on macOS.
2. Open **General → Security → Secure Storage → Password**.
3. Under **Master password providers**, clear **OS X Keystore Integration** and enable **UI Prompt**.
4. Select **Apply and Close**. Open the generator, re-enter the database Password and select **Save password in Eclipse Secure Storage**. Select **Update profile** for a connection profile. For unregistered settings, close the dialog; **Cancel** also saves them.
5. In the prompt, create or enter the master password used to encrypt Eclipse's saved information. Reopen the generator to confirm the database Password is restored.

**UI Prompt still uses encrypted Eclipse Secure Storage.** Only the way Eclipse obtains its master password changes. This setting affects newly saved information throughout Eclipse; existing entries are not migrated automatically. See the official [Secure Storage preferences](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.user/reference/ref-securestorage-prefs.htm) and [how Secure Storage works](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.user/reference/ref-securestorage-works.htm).

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

import org.seasar.doma.Column;
import org.seasar.doma.Entity;

@Entity
public class EmployeeResult {
    /** @domaGenerated */
    @Column(name = "EMPLOYEE_ID")
    private Long employeeId;

    @Column(name = "EMPLOYEE_NAME")
    private String employeeName;

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
- Wildcard-imported types are resolved against the target Java project. Ambiguous names require an explicit import or qualified type instead of guessing. Ordinary imports previously referenced by generated members are removed only when no code or Javadoc uses their short names. Other manual imports, static imports and wildcard imports are retained.

Existing UTF-8 files with a BOM can be updated; the BOM is preserved when writing.

Save existing edits before previewing. At Update, the saved source and modification stamp must still match the preview; dirty editors, changed files and deleted files stop the update. The file is written in its existing character encoding, with Eclipse local-history preservation enabled. Local-history retention follows workspace settings. The comparison covers the target Java file; callers in other files may still need changes when its public API changes.

### Classes from the earlier prototype

For a class with the original `Generated from SELECT result metadata.` header and no ownership tags, the first update can adopt matching simple `@Column(name = ...)` fields and ordinary accessors. Their user comments and custom methods are retained, and ownership tags are added to adopted members. Unmarked old fields absent from the current SQL remain in place, with a preview note, because their ownership cannot be established. Review those fields manually. Arbitrary unmarked field collisions are not overwritten. Immutable entities and unsupported/custom declarations require manual adjustment.

## SQL templates

Simple Doma bind comments with executable test literals, such as `/* employeeId */11`, can remain. JDBC parameter placeholders (`?`) must be replaced with typed sample literals.

Simple expansion directives are prepared automatically for metadata lookup:

```sql
-- No table alias
select /*%expand*/* from employee
-- Metadata SQL: select * from employee

-- Literal table alias
select /*%expand "e" */* from employee e
-- Metadata SQL: select e.* from employee e
```

On **Preview**, **Metadata SQL** displays the prepared SELECT. The saved SQL file remains unchanged. Bind comments and test values, including `IN /* ids */(0)`, can remain. A literal alias can use a quoted SQL identifier; arbitrary alias expressions are not evaluated. The database describes `*` or `alias.*`, so metadata generation includes that table's database columns rather than deriving a projection from an existing Doma entity definition.

### Doma conditions

Nested `/*%if condition */ ... /*%end*/` filters inside an existing WHERE or HAVING clause can remain unchanged. No edits to the SQL file or Metadata SQL are needed.

```sql
SELECT e.id, e.name
FROM employee e
WHERE 1=1
/*%if @isNotBlank(condition.name) */
  AND e.name = /* condition.name */'sample'
/*%end*/
/*%if condition.first || condition.second */
  AND e.status IN (''
    /*%if condition.first */ ,'A' /*%end*/
    /*%if condition.second */ ,'B' /*%end*/
  )
/*%end*/
```

Template syntax and nesting are parsed by Doma's own `SqlParser`, condition-expression syntax by its `ExpressionParser`, and SQL assembly by its `NodePreparedSqlBuilder`. There is no separate plug-in condition grammar or clause-scope validator. Leading logical operators such as `WHERE AND ...` and `HAVING OR ...`, including inside subqueries, are removed by the official builder. No source or Metadata SQL edits are needed.

Since no DAO invocation arguments are supplied, an adapter includes simple filter bodies and test literals without evaluating application conditions or Java code. Constants and Doma's loop flags can be evaluated by Doma itself as described below. Opaque strings, quoted identifiers and bind comments are protected and restored. The visible template retains its condition comments; JDBC receives the official builder's assembled SQL. No result rows are fetched. Filters inside subqueries, CTEs and UNION branches are supported.

Conditional SELECT expressions with `%if`/`%elseif`/`%else` are also supported without rewriting the SQL. For example:

```sql
SELECT e.id
/*%if options.exactName */
  , CASE WHEN e.exact_name IS NULL THEN e.name ELSE e.exact_name END AS full_name
/*%else*/
  , e.name AS full_name
/*%end*/
FROM employee e
```

Conditional choices are assembled separately by the official Doma builder, including SELECT expressions, FROM/JOIN and ORDER BY alternatives that its parser accepts. The generator compares each variant's column names, order and JDBC types before generating a class. All must agree; type-mapping overrides do not bypass this check. A projection `%if` without `%else` also checks the empty branch. Identical condition-expression chains are chosen consistently across repeated occurrences, including UNION branches. Independent chains are combined, up to **64 combinations**, and identical assembled SQL is described once. Simple filter-only `%if`/`%end` bodies remain included, so ordinary optional filters do not multiply the combinations; this optimization follows Doma's syntax tree rather than a second SQL parser. Each description uses `getMetaData()`, never SELECT execution, and shares the existing Preview cancellation and timeout.

If branch metadata differs or the combination limit is exceeded, select the intended concrete SELECT in **Metadata SQL**. Malformed templates are rejected by Doma's parser with its diagnostic code and, when available, a prepared-SQL line number, without exposing raw SQL. The remaining plug-in adapter selects representative branches and loop samples, preserves test literals and prepares literal `%expand` for JDBC metadata; it does not evaluate DAO arguments or application code. SELECT-only checks remain separate from template syntax. This is a generation-time adapter, not an unchanged DAO execution path, and does not prove which expression combinations occur at runtime.

### Loops and constant embedded SQL

Filter loops can remain unchanged, including an embedded constant separator:

```sql
SELECT e.id, e.name FROM employee e
WHERE (
  /*%for item : filter.ids */
    e.id = /*item*/1
    /*%if item_has_next */ /*# "OR" */ /*%end*/
  /*%end*/
)
```

The adapter supplies a two-element sample collection to Doma's own loop builder for a simple filter loop. Doma sets/evaluates `item_has_next` and `item_index`, so separators are emitted between items and never after the last item. Bind values are taken from the SQL test literals, not from the synthetic elements. Index comparisons, negation, alternative separator branches, nested loops and shadowed loop identifiers follow the same official loop/condition implementation. Sample collections are supplied without invoking the application's collection expression. Conditions evaluated within a loop must use only its flags/index and constants; application-dependent conditions continue to use the generation adapter's representative branches.

Constant embedded expressions such as `/*# "OR" */` or `/*# "O" + "R" */` use Doma's expression evaluator and embedded-SQL checks. Application variables, fields, methods, functions and constructors are never evaluated for embedded SQL; runtime fragments such as `/*# orderBy */` need a concrete value in Metadata SQL. Literal variables with test values, such as `/*^ item */1`, retain those test values just like bind comments. The assembled SQL is checked again for a single SELECT before it reaches JDBC.

Loops outside simple filters are described with one and two sample elements, and the same column-metadata comparison applies; variable projection sizes cannot produce a single stable result class. Filter loops do not multiply variant combinations. In addition to the 64-combination limit, expansion is bounded to **256 sample elements per assembled SELECT**, including nested loops. These are representative metadata samples, not the caller's actual values or a proof for arbitrary collection sizes. All metadata descriptions remain cancellable and use the Preview timeout without calling SELECT execution APIs.

**Metadata SQL → SQL syntax** defaults to automatic selection from the JDBC URL: PostgreSQL, MySQL/MariaDB, SQL Server, or generic non-nested comments for Oracle and other URLs. PostgreSQL/SQL Server support nested comments; MySQL supports backslash string escapes. Select the corresponding override for MySQL `NO_BACKSLASH_ESCAPES` or PostgreSQL legacy backslash strings. Automatic selection does not query the database's session mode.

SQL Server CTEs starting with `WITH` need no source edits. Microsoft's JDBC metadata implementation prefixes SQL with `SET FMTONLY ON`, so the generator supplies the required `;` separator only in the SQL passed to JDBC. The Metadata SQL field remains unchanged, and an existing `;WITH` prefix is accepted. Select **Automatic (JDBC URL)** or **SQL Server** under **SQL syntax**. If error 319 persists, **Error details...** includes CTE syntax/separator guidance. See the [Microsoft JDBC implementation](https://github.com/microsoft/mssql-jdbc/blob/v12.8.1/src/main/java/com/microsoft/sqlserver/jdbc/SQLServerPreparedStatement.java).

Locking SELECTs with `FOR UPDATE`, `NOWAIT`, `OF ...` or `SKIP LOCKED` are accepted. The statement is only prepared/described, never executed; multiple statements and data-modifying CTEs remain rejected.

### JDBC errors

SQLState alone may not identify an Oracle error. Use **Error details...** to check the ORA number, vendor code and whether the failure occurred while connecting, preparing the SELECT, or reading column metadata. If the same SQL works in another client, compare that client's JDBC driver and session/current-schema settings. Share only the diagnostic codes and stage when reporting a failure; connection strings, SQL text and raw driver messages are not included in these details.

Existing-class merge and Java-validation failures identify their own operation stages rather than being reported as JDBC driver unloading errors.

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
import java.time.LocalDateTime;
import org.seasar.doma.Column;

// Inside the entity class
@Column(name = "CREATED_DATETIME")
private LocalDateTime createdDatetime;
```

- Rules match the **final Java property name**, including any collision suffix. They do not match the raw SQL column label. `@Column` retains that label.
- Matching is case-sensitive and uses Java regex `Matcher.matches()` (the whole property name). `createdDateTime` does not match `.*Datetime$`.
- The **first matching rule from top to bottom** wins, and takes precedence over JDBC mapping. Unmatched properties keep their JDBC types. An explicit rule can supply a type even when the JDBC type is otherwise unsupported.
- Scope a rule to a class with `com.example.dto.EmployeeResult@.*Datetime$=java.time.LocalDateTime`. The class-name prefix is literal; the property part is a regex. Place a scoped rule before broader rules that could also match.
- Regex capture replacements such as `$1` and the leading-`@` property-pattern convention from Doma-Gen are supported. For example, `@.*(Date)time$=java.time.Local$1Time`.
- Blank lines and lines starting with `#` or `!` are ignored. Surrounding whitespace is trimmed. The last `=` separates pattern and replacement, allowing regexes such as `(?=.*Id$)user.*=java.lang.Long`. Backslashes in regexes are preserved; Java `Properties` unescaping and multiline continuations are not used. Replacements use the established whole-name match, including with reluctant quantifiers and alternations, without appending an unmatched suffix to the Java type.
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

## Relationship to the official generator

[doma-gen](https://github.com/domaframework/doma-gen) is archived and directs users to [doma-codegen-plugin](https://github.com/domaframework/doma-codegen-plugin). Its compatibility matrix identifies CodeGen Plugin **2.x** as the Doma 2 line. The official tools already support generation from SQL results: see [ResultSetMetaReader](https://github.com/domaframework/doma-gen/blob/master/src/main/java/org/seasar/doma/extension/gen/ResultSetMetaReader.java) and the successor's [CodeGenDtoTask](https://github.com/domaframework/doma-codegen-plugin/blob/2.x/codegen/src/main/java/org/seasar/doma/gradle/codegen/task/CodeGenDtoTask.java).

Generation now directly calls **doma-gen 2.28.0's `EntityDescFactory`, `EntityPropertyDescFactory` and `Generator`**. An Eclipse output template and adapters retain private fields, ownership tags, Java naming rules, type overrides and collision-aware imports. SQL parsing and assembly use **Doma Core 2.57.0's `SqlParser` and `NodePreparedSqlBuilder`**. These are actual runtime dependencies, shipped as OSGi bundles in the update site together with FreeMarker and their dependencies. A separate Gradle/Ant installation is not required; the plug-in does not launch the Ant task.

Eclipse connection profiles, preview and existing-source merging remain the plug-in's responsibility. This is not a full DAO invocation: test values are used instead of runtime arguments, and an adapter includes supported filter bodies without evaluating application code. The official `ResultSetMetaReader` executes the SELECT, so column lookup continues to use JDBC prepared-statement metadata before execution. No execution fallback is performed. Existing type/naming compatibility is retained, including FLOAT → Double. See [THIRD_PARTY_NOTICES](plugin/THIRD_PARTY_NOTICES) for bundled dependency versions and licenses.

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
