# Contributing

Contributions are welcome under the [Apache License 2.0](LICENSE). By submitting a contribution, you agree that it may be distributed under that license. No CLA is currently required.

## Development

Use JDK 17+ and Maven 3.8.6+. Run `mvn -f tests/pom.xml test` for the portable core and `mvn verify` for the complete Eclipse plug-in and update site. Import `plugin` into Eclipse PDE for interactive development. Keep core code free of Eclipse and vendor JDBC dependencies. Add a targeted regression test for changes to metadata handling or source generation.

The root is a Tycho reactor. `tests` is intentionally a standalone Maven project: it compiles the core and merger source using Maven JDT libraries, without resolving a p2 target or launching Eclipse. `navigation-tests` is a test fragment that checks Java-to-SQL navigation in a headless Eclipse runtime using real JDT projects. `merge-tests` checks update snapshots, compiler validation, dirty buffers, encoding and local history in real workspace projects. `plugin` holds the runtime, `feature` describes the installable feature, and `repository` builds a p2 site.

## Manual Eclipse acceptance checklist

Before a release, record the Eclipse version, Java runtime, operating system and JDBC driver version used for these checks:

1. Build with `mvn clean verify`. Install the update-site ZIP into a clean Eclipse Java IDE and restart. Confirm the feature is installed without unresolved bundle errors.
2. Open a Java project with Doma and annotation processing. Save a `.sql` file and confirm the context menu appears for a single SQL file in both supported explorers.
3. With a test database, preview a SELECT with JOINs, explicit aliases, nullable columns and an aggregate. Confirm correct field types and original labels. Run a DAO returning the generated class.
4. Generate into a source folder and a new package. Confirm the class opens in the Java editor. Add handwritten methods and comments, change SQL columns/types, and preview the same destination. Confirm the read-only comparison shows both versions and no file is changed before Update. Apply, check custom code/comments and local history, then preview again and confirm Update is disabled with no changes.
5. Change SQL, class name and source folder after preview. Confirm Generate stays disabled until the preview is refreshed.
6. Check Doma dynamic directives, unknown types, duplicate aliases, invalid JDBC credentials and missing driver JARs. Verify useful errors, with no password shown or persisted.
7. Cancel the dialog. Confirm no Java class or modified SQL file was written.
8. Validate resizing, keyboard navigation and password masking. Check Windows, Linux and macOS before claiming support for each platform.
9. Put `.*Datetime$=java.time.LocalDateTime` in a saved project-root `name.properties`. Reopen the generator and confirm the file is initially selected. Preview a `CREATED_DATETIME` column, confirm the override, then clear the file field and confirm JDBC mapping is restored. Try a malformed regex and confirm the line-number error. Change the selected mapping file and confirm Generate requires another preview.
10. Place the cursor on a DAO method name in a service's Java method call. Select **Jump to Doma SQL** from the editor context menu and confirm its SQL file opens. Repeat from a DAO declaration, with an unsaved caller, and across referenced Java projects. Add a dialect variant and confirm the path picker opens and can be canceled. Check a non-DAO method, inline `@Sql` and a missing file: confirm an explanation appears and no files are created. Assign the command a key binding under **General → Keys** and repeat navigation.
11. Edit the target after Preview and confirm Update refuses to apply the stale proposal. Repeat with an unsaved editor and an externally changed file. Check a custom getter body, a removed field referenced by a helper, and an incompatible helper return type: confirm source is retained and an explanation appears. Verify a legacy unmarked class adopts matching members and retains uncertain old fields with a note. Cancel comparisons and the generator and verify no file changed.

## Reporting compatibility

Report the database/server version, JDBC driver coordinates/version, Eclipse/Java/OS versions, redacted sample SQL, expected Java fields and whether `getMetaData()` works before execution. Do not include passwords, production rows, private hostnames or complete connection URLs.

Vendor-specific behavior must be handled by an explicit mapping/strategy and tested on that database before being advertised. Never silently fall back to `Object` or execute arbitrary templates to infer types.

## Publishing

The public repository is `vipt/doma-result-generator`; GitHub Pages serves `https://vipt.github.io/doma-result-generator/`. Configure **Settings → Pages → Source → GitHub Actions**. A push to `main` runs tests, assembles `_site` with `scripts/build_pages.py`, and deploys the Pages artifact. Pull requests do not deploy. `_site` contains the static `site` files, p2 metadata/features/plugins, a ZIP download and build provenance in `build.json`.

Keep root/plugin/feature license files synchronized and run the manual checks before a release. GitHub Releases are not created automatically: attach the generated repository ZIP when creating a versioned release. When changing the Maven project version, update the distribution archive path in `scripts/build_pages.py` and installation documentation as well.
