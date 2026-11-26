# Doma Result Generator

[Install / update site](https://vipt.github.io/doma-result-generator/) · [日本語](README.ja.md) · [Contributing](CONTRIBUTING.md) · [Apache-2.0](LICENSE)

An independent Eclipse plug-in that generates a Doma 2 entity from the columns returned by a SELECT. Designed for joins, projections, aggregates and SQL result sets that do not match a single table.

It also generates DAO interfaces, entities, optional entity listeners and SQL files from selected database tables using official **doma-gen 2.28.0**.

**Status: initial prototype.** The core is exercised with H2 and Doma 2.57.0. Other database compatibility depends on the JDBC driver and official doma-gen dialect; it is not a claim of compatibility with every database. This is not an official Doma or Eclipse project.

## Workflow

For SELECT result classes, use the workflow below. For table-based generation, see [Generate from database tables](#generate-from-database-tables).

1. Save a `.sql` file in an Eclipse Java project.
2. Right-click the SQL file in Package Explorer or Project Explorer, or inside its open SQL editor, and select **Generate Doma Result Class...**.
3. Select a saved **Connection profile**, or use **Driver JARs → Download...** to fetch a catalog driver (or **Browse...** for a manually supplied JAR). Enter the JDBC URL and credentials. Drivers are downloaded separately and are not bundled in the update site.
4. Select a Java source folder, package and class name. Optionally select a UTF-8 type mapping file; `name.properties` in the project root is initially selected when present.
5. Review **Metadata SQL** and select **Preview**. Dummy arguments are supplied automatically and Doma builds the SQL. Build the project first when using DTOs or application constants. Changes do not modify the SQL file.
6. If the file exists, choose **Merge**, **Overwrite** or **Cancel**. Review **Proposed Java** and the comparison, then select **Generate** for a new class or the chosen **Merge/Overwrite** action for an existing file.

Preview uses the official doma-gen SQL-result reader: it executes the SELECT and reads its result-set metadata without iterating rows. Use the intended JDBC connection and review Metadata SQL before Preview.

Generated fields are private, with public getters/setters. Annotations and property types use imports and short names, including `@Entity`, `@Column`, `BigDecimal` and `LocalDateTime`. Types in `java.lang` or the entity's own package do not need imports. If a short name conflicts with another type or retained handwritten code, that reference stays qualified.

Connection profiles are shared across projects in the same workspace. The last selected profile and Package are remembered per project, including on **Cancel** and after restarting Eclipse. With **Unregistered (project settings)**, driver JAR paths, driver class, JDBC URL and username are remembered per project as before. Package defaults to the exact previously entered text, or `com.example.dto` on first use. These settings are not written into project files. Source folder, class name and edited Metadata SQL are not remembered.

From a SQL editor, the generator reads the current Eclipse text buffer, including unsaved edits, without saving the file. An editor whose unsaved content cannot be read asks you to save first. From Explorer, it reads the saved SQL file.

SQL extensions are case-insensitive in both the menu and default class name: `selectAll.SQL` defaults to `SelectAllResult`.

**Save password in Eclipse Secure Storage** is off by default. Select it to save the Password field using [Eclipse encrypted secure preferences](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/secure_storage_architecture.htm); Eclipse may ask to unlock its storage. To remove the saved password, clear the checkbox or Password field, then select **Update profile** for a profile or close the dialog for unregistered settings. Passwords are never saved in ordinary workspace preferences. Changing the driver class, URL or username clears the restored password. Use the separate Username and Password fields: URLs with embedded credentials, such as Oracle `user/password@...`, query `password=...`, or URL user info, are not remembered. If Secure Storage cannot be read or updated, the dialog reports it without falling back to plaintext.

Raw driver exceptions are not logged or displayed, since they may include connection details. Driver errors show the operation stage, SQLState, vendor error code and Oracle ORA number when available. **Error details...** shows these codes and phase-specific guidance; generator errors give actionable explanations. Use your normal trusted driver and database connection settings. Driver-specific timeout and SSL settings can be supplied in the JDBC URL according to the driver's documentation.

### Downloading JDBC drivers

Both the SELECT result and table-generation connection panels offer **Download...** next to **Driver JARs → Browse...**.

1. Choose the database and a catalog driver version, then review its compatibility notes, included JARs, vendor documentation and license link.
2. Choose **Download and use**. The plug-in fetches fixed artifacts from `https://repo.maven.apache.org/maven2/`, verifies their expected size, SHA-256 and JAR contents, then fills **Driver JARs** and **Driver class**. Required non-optional dependencies are included. The database URL, username and password are never sent to the repository. URL/username fields are retained; changing the driver class clears the password as usual, while reusing the same class retains it.
3. **Keep additional JARs already listed** preserves manually supplied wallet/authentication libraries. Recognizable old copies of the selected main driver and its included dependencies are replaced; downloaded JARs take precedence. Clear the checkbox to use only the downloaded JARs.
4. Enter/check the JDBC URL and credentials, then use Preview. To persist changes to a shared connection profile, choose **Update profile** (or **Save as...**).

Catalog checked on **2026-11-26**:

| Database | Catalog versions | Included dependency JARs |
| --- | --- | --- |
| Oracle | ojdbc11 23.26.4.0.0 | None for basic Thin connections |
| SQL Server | 13.6.2.jre11 | None for basic SQL authentication |
| PostgreSQL | 42.7.15 | checker-qual 3.55.1 |
| MySQL | 26.10.0 (MySQL 8.4+), 9.7.0 (MySQL 8.0+) | protobuf-java 4.36.1 / 4.31.1 |
| MariaDB | 4.0.0 (Java 17+), 3.5.11 | None for basic connections |
| Db2 LUW | 12.1.5.0 | None for basic LUW connections |
| H2 | 2.5.252 | None |

JARs are cached under the plug-in's Eclipse workspace metadata and rechecked before reuse, so other projects can reuse them without downloading again. Opening the generator does not access the repository or automatically upgrade a profile's existing driver. New catalog versions arrive with plug-in updates; **Browse...** remains available for other driver versions. The cache is not copied into projects or added to Maven/Gradle dependencies.

Downloads use **Preferences → General → Network Connections** when Eclipse's proxy service is available (otherwise the Java runtime's networking settings), plus the Eclipse runtime JRE's TLS trust configuration. **Cancel** and the configurable download timeout stop waiting without applying connection-field changes. Incomplete files are removed; already completed verified JARs can remain cached. TLS certificate verification is retained.

Driver availability does not guarantee every database/server/authentication combination. Oracle wallets/SSO, SQL Server Azure/Windows authentication, Db2 z/OS/i licensing and similar optional features may require additional JARs or native libraries. MariaDB 4.0 defaults to TLS `verify-full`; match the driver version/settings to the server. MariaDB table metadata uses doma-gen's official MySQL dialect; the JDBC URL and SQL are unchanged. No database connection is needed just to download a driver.

Vendor references: [Oracle](https://www.oracle.com/database/technologies/appdev/jdbc-downloads.html), [SQL Server](https://learn.microsoft.com/en-us/sql/connect/jdbc/download-microsoft-jdbc-driver-for-sql-server), [PostgreSQL](https://jdbc.postgresql.org/download/), [MySQL compatibility](https://dev.mysql.com/doc/connector-j/en/connector-j-versions.html), [MariaDB releases](https://mariadb.org/connector-java/all-releases/), [Db2 artifact/license](https://central.sonatype.com/artifact/com.ibm.db2/jcc/12.1.5.0), [H2](https://h2database.com/html/download.html). Vendor/dependency licenses apply to downloaded files; this does not change the plug-in's Apache-2.0 license. Tests cover stubbed downloads, checksum/cache/partial-file handling, cancellation/timeouts and Eclipse proxy selection. Real JARs for all seven databases/nine catalog releases were checked for hashes, Java loading and URL recognition; H2 also exercised an actual Java HTTPS download. Other real server connections are not claimed by these checks.

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

## Generate from database tables

1. Select a Java project, source folder or file in Explorer. Choose **Generate Doma DAO, Entity and SQL...** from its context menu, or from **Project**.
2. Select an existing **Connection profile**, or enter JDBC settings. Profiles, optional encrypted passwords, per-project unregistered settings and timeouts are shared with the SELECT result generator. **Save as...** registers the currently entered connection without retyping it; **Update profile** saves edits to a shared profile.
3. Choose the Java source folder and SQL resource folder, entity/DAO packages, optional EntityListener, `name.properties`, and an optional existing doma-gen FreeMarker template directory. Table-generation defaults are remembered separately per project, including when canceled.
4. In **Tables**, choose **Load tables**. Optionally filter by schema (a JDBC schema pattern), include views, filter the list, and check the tables to generate. **Select shown** selects the filtered rows; hidden checked tables stay selected until **Clear selection**.
5. Choose **Preview**. **Proposed files** shows a checkbox tree on the left and the selected file's read-only contents on the right. Drag the divider to resize the panes. Files are grouped by output folder/package, with DAO folders below `META-INF` for SQL. New files are **Create**; existing files initially use **Merge**.
6. Check the files to generate/update; unchecked files are skipped. Checking a parent includes its descendants, and a partial selection shows an indeterminate parent. Use **Check all / Uncheck all** and **Expand / Collapse** as needed. **Merge checked / Overwrite checked** sets the action only for checked existing files; new files remain Create. The right-hand **Selected file action** can also change one checked existing file. Each row shows its action and conflict/change status.
7. Choose **Compare checked changes...**, inspect the selected files' read-only differences, then **Generate checked files**. Changing checks or actions requires comparing again. Unchecked files are excluded from comparison, saving and baseline updates, and their conflicts/unsaved edits do not block the checked batch. Dirty/stale checked files or checked merge conflicts prevent saving. Preview/cancellation never writes generated files.

This uses official doma-gen's `TableMetaReader`, entity/DAO/listener/SQL descriptor factories and FreeMarker `Generator`, the same table generation pipeline as `Gen` invoked from Gradle/Ant. A Gradle installation or Gradle task is not required. No custom SQL parsing or template condition evaluator is used. Existing `dao.ftl`, `entityListener.ftl`, `entity.ftl` and `*.sql.ftl` templates can be reused by selecting their directory; missing templates fall back to doma-gen's defaults. Templates referencing application annotations/utilities still require those dependencies in the target project. Default entity fields are private with public accessors and imported types; a supplied `entity.ftl` controls its own output.

Primary keys, identity columns, version columns and JDBC types follow doma-gen. Primary keys produce `selectById.sql`; a version column also produces `selectByIdAndVersion.sql`. Without a primary key there is no default SELECT SQL. SQL files go under `META-INF/<qualified DAO name as folders>/`. **Include schema in @Table** affects the entity annotation; SQL text follows the official templates, so the application's default schema must match, or its existing SQL templates must qualify table names. Duplicate destinations across selected schemas are rejected. The generator reads database metadata (and may run doma-gen's metadata SELECTs); it does not modify database data or run the generated INSERT/UPDATE/DELETE methods.

For existing **Java** without a previous generation baseline, Merge preserves existing class annotations, members and comments, and adds missing class annotations/members/imports. It does not claim existing members as generated. Subsequent merges compare the previous generator output with current and newly generated Java by member, preserving custom additions and comments; overlapping edits stop the merge. For existing **SQL** without a baseline, Merge preserves the whole file. Choose Overwrite explicitly to replace it. Later SQL merges use Eclipse's three-way line differencer and stop on overlapping edits.

Baselines contain the last generator output and are saved only in Eclipse workspace metadata after generation; they are not added to the project or Git. A different workspace has no baseline. Overwrite replaces the complete file, including handwritten code/comments; existing encodings, BOMs and line endings are retained and Eclipse local history is kept. Always inspect the comparison, especially existing Java class annotations/types and custom templates. A batch does not remove obsolete files outside its proposed output list.

### Edit FTL templates

Choose **Configuration → Edit templates...** in the table generator. It edits the selected template directory. If the directory field is blank, it opens `<project>/doma-gen-templates`; no folder/files are created until you choose **Copy missing defaults** or **New...**.

1. Choose **Copy missing defaults** to create editable copies of `entity.ftl`, `dao.ftl`, `entityListener.ftl`, `selectById.sql.ftl`, `selectByIdAndVersion.sql.ftl` and `lib.ftl`. Existing files, including empty ones, are kept. The copied entity template uses the same private fields as the plug-in's defaults.
2. Select a file, edit it and choose **Save**. The editor uses UTF-8 and validates syntax with FreeMarker itself. Unsaved edits offer **Save / Discard / Cancel** when switching or closing. **New...** adds a template such as `selectAll.sql.ftl`; **Reload** reads changes made outside the dialog.
3. Close the template editor and choose **Preview** again. Opening the editor invalidates the previous generated preview; each Preview loads saved template contents afresh. Template evaluation errors identify the FTL file and line. Data-model names/properties follow official doma-gen; its template headers identify the model classes.
4. Review the generated files and their merge/overwrite actions, compare the changes, then generate.

`lib.ftl` is the shared template imported by official defaults (for example, author/copyright settings). The editor also lists nested `.ftl` files. Templates with unsaved edits in an Eclipse text editor, or files changed on disk since loading, cannot be overwritten here; save/revert the other editor and Reload first. Explicit **Save / New / Copy** writes persist even when you later cancel the generator. Editing templates requires no database connection. These FTL files configure table-based DAO/Entity/SQL generation.

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

Select the existing class's source folder, package and name, then press **Preview**. Before connecting to the database, choose **Merge**, **Overwrite** or **Cancel**. The choice appears for each preview, with Merge selected by default. New files do not show this prompt.

- **Merge** starts from the existing class and preserves compatible existing fields, handwritten methods, constructors and comments.
- **Overwrite** replaces the entire file with the newly generated class, removing custom methods, constructors, comments and other top-level types. It can regenerate a file even when missing ownership tags or incompatible handwritten fields prevent merging.
- **Cancel**, or closing the choice dialog, stops previewing before any database connection or Java-file write.

After choosing, review the read-only Eclipse comparison: **Current saved class** on the left and **Proposed merge** or **Proposed overwrite** on the right. Close it and select **Merge** or **Overwrite** in the generator dialog to save. **Compare changes...** reopens the comparison; **Proposed Java** shows the complete proposed file. Closing the comparison or canceling the generator writes nothing. With no changes, the final action stays disabled.

The preservation and ownership rules below apply to **Merge**.

Generated fields and accessors carry the Javadoc ownership tag `@domaGenerated`. The updater uses JDT syntax nodes and source ranges to make targeted changes:

- New result columns add fields and accessors. Type changes update the field and simple getter/setter signatures. `name.properties` rules apply to these updates too.
- Handwritten methods, constructors, fields, imports, class annotations, nested types and other top-level types are retained. Customized accessor bodies are retained when their signatures remain compatible.
- Existing unmarked fields are reused when Java types and Doma column mappings match. Their declarations and accessors stay unchanged, without ownership markers or new annotations; new result columns can still be added.
- Javadoc, line comments and block comments are kept. When an obsolete generated declaration can be removed, its user comments remain as standalone comments. Only generator ownership tags are removed with the obsolete declaration.
- A marked obsolete field and its simple marked accessors can be removed. If retained code refers to that field, an accessor has a custom body, or the field has custom initializers/annotations, the update is stopped with an explanation.
- A handwritten member collision or an incompatible customized accessor stops the update. A private JDT working copy also checks the proposed class using the target project's Java build path. Java errors, including type errors in handwritten code, must be resolved before applying the update. The private validation does not save the class.
- Wildcard-imported types are resolved against the target Java project. Ambiguous names require an explicit import or qualified type instead of guessing. Ordinary imports previously referenced by generated members are removed only when no code or Javadoc uses their short names. Other manual imports, static imports and wildcard imports are retained.

Existing UTF-8 files with a BOM can be updated; the BOM is preserved when writing.

Save existing edits before previewing. **Both Merge and Overwrite** require the saved source and modification stamp to match the preview; dirty editors, changed files and deleted files stop the write. Overwrite also validates the generated Java and preserves the existing encoding, BOM and line delimiters. Eclipse local-history preservation is enabled; retention follows workspace settings. The comparison covers the target Java file, so callers in other files may still need changes.

### Classes from the earlier prototype

For a class with the original `Generated from SELECT result metadata.` header and no ownership tags, the first update can adopt matching simple `@Column(name = ...)` fields and ordinary accessors. Their user comments and custom methods are retained, and ownership tags are added to adopted members. Unmarked old fields absent from the current SQL remain in place, with a preview note, because their ownership cannot be established. Review those fields manually. Arbitrary unmarked field collisions are not overwritten. Immutable entities and unsupported/custom declarations require manual adjustment.

### Existing entities without ownership tags

An existing `@Entity` with `private String code;` can reuse that field for a string-valued `CODE` result column, without adding `@Column` or `@domaGenerated`. Column comparisons ignore case, as Doma's result mapping does. Explicit literal `@Column(name = "...")` mappings and the class's `@Entity(naming = NamingType....)` convention are honored; unspecified naming uses the standard NONE convention.

In Merge mode, constructors, `equals`, `hashCode`, `toString`, comments and handwritten fields absent from the SELECT stay in place. If everything matches, there is no diff and Merge stays disabled. Additional columns produce new generated members for comparison.

Reuse does not grant permission to change the existing field's type or remove it on subsequent previews. Type/mapping differences and static, final, transient or Doma-excluded fields stop the merge with a specific explanation. Custom `Config.getNaming()` implementations and constant expressions in `@Column` names are not executed/evaluated by this source check.

## SQL input and the official generator

The plug-in calls **Doma 2.57.0's unmodified `SqlParser`, `ExpressionEvaluator` and `NodePreparedSqlBuilder`**, then passes the prepared SELECT to **doma-gen 2.28.0's `ResultSetMetaReader.read(sql)`** and its entity-description/Java-generation APIs. Parsing, IF/ELSE evaluation, FOR loops, embedded/literal directives and removal of redundant AND/OR operators use Doma itself. There is no custom SQL parser, expression evaluator, branch chooser, SQL repair or raw-SQL fallback.

Provide one SELECT in **Metadata SQL**, together with the JDBC connection and output package/class name. On Preview, the official reader prepares and **executes the SELECT with `executeQuery()`**, reads `ResultSet.getMetaData()`, and closes the result set and statement. It does not iterate result rows, but the database still executes the SELECT. The Eclipse integration retains its existing cancellation, timeout, connection profiles and source comparison/update behavior.

There is no argument input UI. An adapter reads the official parsed nodes and prepares dummy values using the declared parameter/generic types of the DAO corresponding to `META-INF/<fully qualified DAO>/<method>.sql`, with SQL test literals and expression type hints for standalone scalar parameters. Supported values include numbers, strings, booleans, dates, arrays, one-element collections, DTOs with a no-argument constructor and records. Basic DTO fields and SQL-referenced nested DTOs are initialized. Real search criteria and matching database rows are unnecessary: an empty result still provides column metadata.

Compiled application DTOs, constants and methods are loaded automatically from the project's runtime classpath, including dependencies and referenced projects. Build the project first; no additional classpath field is required. Missing DTO types, dependencies or usable constructors produce actionable errors. The adapter does not invent DTO classes or substitute method results. Basic standalone SQL can infer argument types from test literals and expression hints.

```sql
SELECT e.id, e.name FROM employee e WHERE AND e.id = /*id*/1
```

Doma removes the leading AND in this example and builds `e.id = ?`. Its standard `PreparedSqlParameterBinder` binds typed dummy values before the official generator executes the SELECT. Neither the source SQL file nor the displayed Metadata SQL is rewritten automatically. For `%expand`, the official expansion hook receives `*` for JDBC column discovery; Doma evaluates and applies the alias. Thus `/*%expand "e"*/*` becomes `e.*`, describing the actual columns returned by the database.

Generation describes one path selected by Doma using the dummy values. WHERE-only filters normally do not change result column names/types. If arguments change projected columns, aliases, types or joined tables, the generated class is not guaranteed to represent every path: **review Proposed Java**. Project methods are actually invoked and must support the dummy values.

The SELECT-only input check remains in place before template processing and before executing the prepared SQL. SQL syntax selects quoting/comment rules for that check and the database dialect; it does not make Doma's standard parser accept unsupported syntax. SQL Server CTEs are executed without injected wrappers or SET FMTONLY preambles. Doma errors show codes and line numbers; JDBC errors show their codes and stage, without raw SQL, argument values or connection details.

The official generator's dialect maps JDBC result types to Java types. Existing name.properties overrides, private fields, imports and handwritten-source preservation remain. The official runtime libraries are bundled; a separate Ant/Gradle installation is unnecessary. The SQL file is never modified.

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

### Edit name.properties

Both generation dialogs offer **Edit...** beside the mapping file's **Browse...** button: **Type mappings (optional)** for SELECT results and **name.properties (optional)** for tables. Select your existing file or leave the path blank to edit `<project>/name.properties`.

1. Choose **Edit...**, change the plain-text rules, and select **Save**. A missing file starts with a commented example; no file is created until Save. Rules use `pattern=fully.qualified.JavaType`, for example `.*Datetime$=java.time.LocalDateTime`, without spaces around `=` for compatibility with official table generation.
2. Save validates regular expressions and Java type-name syntax using the existing mapping validator. Comments, rule order, regular-expression escapes and formatting are retained as UTF-8. Use types available to the target project and verify their Doma compatibility by building the project; review capture-based replacements in Preview.
3. Close the editor and select **Preview** again. Opening the editor invalidates the prior generated preview. The saved rules are reloaded by both SELECT result and table generation; review the proposed files/differences before applying them.

**Reload** reads external changes. Unsaved changes offer **Save / Discard / Cancel**. A dirty Eclipse text-editor buffer or on-disk changes since loading prevent overwriting. Editing needs no database connection, and explicit saves remain if the generator is later canceled.

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
- A JDBC driver and database access allowing the SELECT used by official doma-gen.
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

Tests cover official SQL-result reader calls, SELECT execution without row iteration, join/aggregate metadata, empty results, driver isolation, names/aliases, ordered name rules and Java 8 compilation with the real Doma annotation processor. Integration tests compile generated entities and DAOs, then read actual H2 rows through Doma, including an overridden LocalDateTime property and a custom domain.

## Relationship to the official generator

[doma-gen](https://github.com/domaframework/doma-gen) is archived and directs users to [doma-codegen-plugin](https://github.com/domaframework/doma-codegen-plugin). Its compatibility matrix identifies CodeGen Plugin **2.x** as the Doma 2 line. The official tools already support generation from SQL results: see [ResultSetMetaReader](https://github.com/domaframework/doma-gen/blob/master/src/main/java/org/seasar/doma/extension/gen/ResultSetMetaReader.java) and the successor's [CodeGenDtoTask](https://github.com/domaframework/doma-codegen-plugin/blob/2.x/codegen/src/main/java/org/seasar/doma/gradle/codegen/task/CodeGenDtoTask.java).

Generation now directly calls **doma-gen 2.28.0's `EntityDescFactory`, `EntityPropertyDescFactory` and `Generator`**. An Eclipse output template and adapters retain private fields, ownership tags, Java naming rules, type overrides and collision-aware imports. SQL result-column lookup directly uses **doma-gen's `ResultSetMetaReader`**, and JDBC-to-Java types use its `GenDialect`. These are actual runtime dependencies, shipped as OSGi bundles in the update site together with FreeMarker and their dependencies. A separate Gradle/Ant installation is not required; the plug-in does not launch the Ant task.

Eclipse connection profiles, preview, automatic dummy-argument preparation and existing-source merging remain the plug-in's responsibility. Doma's unmodified parser, evaluator, SQL builder and parameter binder process the template before the official `ResultSetMetaReader` executes the SELECT and reads result metadata. No manual argument or classpath input is needed. See [THIRD_PARTY_NOTICES](plugin/THIRD_PARTY_NOTICES) for bundled dependency versions and licenses.

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
- [Official ResultSetMetaReader](https://github.com/domaframework/doma-gen/blob/master/src/main/java/org/seasar/doma/extension/gen/ResultSetMetaReader.java)
- [Eclipse menu contributions](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/extension-points/org_eclipse_ui_menus.html)

## License

[Apache License 2.0](LICENSE). See [NOTICE](NOTICE). Generated Java source is not given a license header by this tool; apply your project's licensing policy to generated files.
