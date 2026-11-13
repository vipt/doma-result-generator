/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.merge;

import static org.junit.jupiter.api.Assertions.*;
import io.github.domaresult.core.EntityGenerator;
import io.github.domaresult.core.GenerationException;
import io.github.domaresult.core.ResultColumn;
import io.github.domaresult.core.SourceUpdatePlan;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntityMergerTest {
    @TempDir Path directory;

    private String generated(ResultColumn... columns) {
        return new EntityGenerator().generate("example", "Result", List.of(columns));
    }
    private ResultColumn column(String label, String type) { return new ResultColumn(label, type); }
    private String custom(String source, String members) {
        int close = source.lastIndexOf('}');
        return source.substring(0, close) + members + source.substring(close);
    }
    private String merge(String before, String proposed) { return new EntityMerger().merge(before, proposed).source(); }

    @Test void unchangedClassHasNoDiffAndRepeatedMergeIsIdempotent() {
        String source = generated(column("ID", "java.lang.Integer"));
        assertEquals(source, merge(source, source));
        String next = generated(column("ID", "java.lang.Integer"), column("NAME", "java.lang.String"));
        String merged = merge(source, next);
        assertEquals(merged, merge(merged, next));
    }

    @Test void addsColumnsAndPreservesHandwrittenMethodsCommentsImportsAndFieldsVerbatim() throws Exception {
        String source = generated(column("ID", "java.lang.Integer"))
                .replace("package example;", "/* License header: keep */\npackage example;\n\nimport java.util.Locale;");
        String manual = """

            // Handwritten helper: 日本語のコメント
            @org.seasar.doma.Transient
            private String displayPrefix = "id=";

            /** A handwritten method, including its formatting. */
            public String display() {
                // This line must stay exactly as written.
                return displayPrefix + String.valueOf(id).toUpperCase(Locale.ROOT);
            }
            static class Helper { /* nested comment */ }
            """;
        source = custom(source, manual);
        String merged = merge(source, generated(column("ID", "java.lang.Integer"), column("NAME", "java.lang.String")));
        assertTrue(merged.contains(manual));
        assertTrue(merged.contains("/* License header: keep */"));
        assertTrue(merged.contains("import java.util.Locale;"));
        assertTrue(merged.contains("private String name;"));
        assertTrue(merged.contains("getName()"));
        compile(merged);
    }

    @Test void updatesFieldAndSimpleAccessorTypesWhileKeepingAllComments() throws Exception {
        String source = generated(column("ID", "java.lang.Integer"))
                .replace("Integer id;", "Integer /* field note */ id; // trailing field note")
                .replace("return id;", "// getter explanation\n        return /* inline return */ id;")
                .replace("this.id = id;", "/* setter explanation */ this.id = id;");
        String merged = merge(source, generated(column("ID", "java.lang.Long")));
        for (String comment : List.of("/* field note */", "// trailing field note", "// getter explanation", "/* inline return */", "/* setter explanation */")) {
            assertTrue(merged.contains(comment), comment);
        }
        assertTrue(merged.contains("Long /* field note */ id;"));
        assertTrue(merged.contains("public Long getId()"));
        assertTrue(merged.contains("setId(Long id)"));
        compile(merged);
    }

    @Test void preservesCommentsInsideQualifiedTypeWhenReplacingTheType() throws Exception {
        String source = generated(column("ID", "java.lang.Integer"))
                .replace("Integer id;", "java./* special type note */lang.Integer id;");
        String merged = merge(source, generated(column("ID", "java.lang.Long")));
        assertTrue(merged.contains("/* special type note */ Long id;"));
        compile(merged);
    }

    @Test void removesOwnedObsoleteMembersAndKeepsTheirUserComments() throws Exception {
        String source = generated(column("ID", "java.lang.Integer"), column("OLD_VALUE", "java.lang.String"))
                .replace("String oldValue;", "String oldValue; // keep trailing old note")
                .replace("return oldValue;", "// keep getter note\n        return oldValue;")
                .replace("this.oldValue = oldValue;", "/* keep setter note */ this.oldValue = oldValue;")
                .replace("@Column(name = \"OLD_VALUE\")", "@Column(name = \"OLD_VALUE\") /* keep column note */");
        source = custom(source, "\n    /** Handwritten helper. */\n    public int answer() { return 42; }\n");
        String merged = merge(source, generated(column("ID", "java.lang.Integer")));
        assertFalse(merged.contains("getOldValue()"));
        assertFalse(merged.contains("setOldValue("));
        assertFalse(merged.contains("String oldValue;"));
        for (String note : List.of("// keep trailing old note", "// keep getter note", "/* keep setter note */", "/* keep column note */", "/** Handwritten helper. */")) {
            assertTrue(merged.contains(note), note);
        }
        assertEquals(merged, merge(merged, generated(column("ID", "java.lang.Integer"))));
        compile(merged);
    }

    @Test void preservesUserJavadocOnRemovedGeneratedMembersAndRemovesOnlyOwnershipTag() throws Exception {
        String source = generated(column("ID", "java.lang.Integer"), column("OLD", "java.lang.String"))
                .replace("/** @domaGenerated */\n    @Column(name = \"OLD\")",
                        "/** User note about old field.\n     * @domaGenerated\n     */\n    @Column(name = \"OLD\")");
        String merged = merge(source, generated(column("ID", "java.lang.Integer")));
        assertTrue(merged.contains("User note about old field."));
        assertEquals(merged, merge(merged, generated(column("ID", "java.lang.Integer"))));
        compile(merged);
    }

    @Test void manualReferencePreventsRemovingAField() {
        String source = custom(generated(column("ID", "java.lang.Integer"), column("OLD", "java.lang.String")),
                "\n    public String display() { return old; }\n");
        var error = assertThrows(GenerationException.class, () -> merge(source, generated(column("ID", "java.lang.Integer"))));
        assertTrue(error.getMessage().contains("retained code references"));
        assertTrue(source.contains("return old;"));
    }

    @Test void customizedAccessorBodyPreventsRemovalOrIncompatibleTypeChange() {
        String source = generated(column("ID", "java.lang.Integer"), column("NAME", "java.lang.String"))
                .replace("return name;", "return name == null ? \"\" : name.trim();");
        assertEquals(source, merge(source, source));
        assertThrows(GenerationException.class, () -> merge(source, generated(column("ID", "java.lang.Integer"))));
        assertThrows(GenerationException.class, () -> merge(source, generated(column("ID", "java.lang.Integer"), column("NAME", "java.lang.Long"))));
    }

    @Test void customSetterBodyIsPreservedAndBlocksIncompatibleChanges() {
        String source = generated(column("ID", "java.lang.Integer"))
                .replace("this.id = id;", "this.id = java.util.Objects.requireNonNull(id);");
        assertEquals(source, merge(source, source));
        assertThrows(GenerationException.class, () -> merge(source, generated(column("ID", "java.lang.Long"))));
    }

    @Test void addingAnotherColumnKeepsCustomizedAccessorBodyExactly() throws Exception {
        String source = generated(column("NAME", "java.lang.String"))
                .replace("return name;", "// manual normalization\n        return name == null ? \"\" : name.trim();");
        String merged = merge(source, generated(column("NAME", "java.lang.String"), column("ID", "java.lang.Long")));
        assertTrue(merged.contains("// manual normalization\n        return name == null ? \"\" : name.trim();"));
        compile(merged);
    }

    @Test void constructorsClassAnnotationsAndOtherTopLevelTypesArePreserved() throws Exception {
        String source = generated(column("ID", "java.lang.Integer"))
                .replace("@Entity", "@Deprecated\n@Entity");
        String constructors = "\n    public Result() {}\n    /** Custom constructor. */\n    public Result(Integer value) { this.id = value; }\n";
        source = custom(source, constructors) + "\nclass HandwrittenCompanion { /* keep */ }\n";
        String merged = merge(source, generated(column("ID", "java.lang.Integer"), column("NAME", "java.lang.String")));
        assertTrue(merged.contains(constructors));
        assertTrue(merged.contains("@Deprecated\n@Entity"));
        assertTrue(merged.endsWith("class HandwrittenCompanion { /* keep */ }\n"));
        compile(merged);
    }

    @Test void supplementaryUnicodeIdentifiersHaveConsistentAccessors() throws Exception {
        String source = generated(column("𐐨value", "java.lang.Integer"));
        String merged = merge(source, generated(column("𐐨value", "java.lang.Long")));
        assertTrue(merged.contains("get𐐀value()"));
        compile(merged);
    }

    @Test void updatesColumnLabelAndPreservesOtherAnnotationArguments() throws Exception {
        String source = generated(column("USER_ID", "java.lang.Integer"))
                .replace("name = \"USER_ID\"", "name = \"USER_ID\", insertable = false, updatable = false");
        String merged = merge(source, generated(column("user_id", "java.lang.Integer")));
        assertTrue(merged.contains("name = \"user_id\", insertable = false, updatable = false"));
        compile(merged);
    }

    @Test void importsAndCustomOverloadsAreRetainedDuringTypeChanges() throws Exception {
        String source = generated(column("ID", "java.lang.Integer"))
                .replace("package example;", "package example;\nimport java.lang.Integer;")
                .replace("java.lang.Integer", "Integer").replace("import Integer;", "import java.lang.Integer;");
        String overload = "\n    /** Manual overload. */\n    public void setId(String value) { id = Integer.valueOf(value); }\n";
        // Keep a manual overload that does not assign the changed field.
        overload = overload.replace("id = Integer.valueOf(value);", "System.out.println(value);");
        source = custom(source, overload);
        String merged = merge(source, generated(column("ID", "java.lang.Long")));
        assertTrue(merged.contains(overload));
        assertTrue(merged.contains("setId(Long id)"));
        compile(merged);
    }

    @Test void typeChangeThatWouldCollideWithHandwrittenOverloadIsBlocked() {
        String source = custom(generated(column("ID", "java.lang.Integer")),
                "\n    public void setId(java.lang.Long value) { System.out.println(value); }\n");
        assertThrows(GenerationException.class, () -> merge(source, generated(column("ID", "java.lang.Long"))));
    }

    @Test void adoptsMatchingLegacyMembersAndPreservesLegacyCommentsAndUncertainObsoleteFields() throws Exception {
        String source = generated(column("ID", "java.lang.Integer"), column("OLD", "java.lang.String"))
                .replace("    /** @domaGenerated */\n", "")
                .replace("@Column(name = \"ID\")", "/** Customer identifier. */\n    @Column(name = \"ID\")");
        String helper = "\n    // manual\n    public String describe() { return \"kept\"; }\n";
        source = custom(source, helper);
        var result = new EntityMerger().merge(source, generated(column("ID", "java.lang.Long")));
        assertTrue(result.source().contains("Customer identifier."));
        assertTrue(result.source().contains(helper));
        assertTrue(result.source().contains("String old;"));
        assertTrue(result.source().contains("private Long id;"));
        assertEquals(1, result.notes().size());
        assertEquals(result.source(), merge(result.source(), generated(column("ID", "java.lang.Long"))));
        compile(result.source());
    }

    @Test void handwrittenUnmarkedFieldCollisionIsNeverOverwritten() {
        String source = "package example; @org.seasar.doma.Entity public class Result { Integer id; }";
        assertThrows(GenerationException.class, () -> merge(source, generated(column("ID", "java.lang.Integer"))));
    }

    @Test void anUnmarkedFieldInAnAlreadyManagedClassIsNotSilentlyReadopted() {
        String source = generated(column("ID", "java.lang.Integer"))
                .replace("/** @domaGenerated */\n    @Column", "/** Handwritten field now. */\n    @Column");
        assertThrows(GenerationException.class, () -> merge(source, generated(column("ID", "java.lang.Long"))));
    }

    @Test void handwrittenAccessorsWithCompatibleSignaturesAreKeptWhenAddingAField() throws Exception {
        String source = """
                package example;
                @org.seasar.doma.Entity public class Result {
                    /** Handwritten API. */
                    public java.lang.Integer getId() { return 17; }
                    public void setId(java.lang.Integer id) { System.out.println(id); }
                }
                """;
        String merged = merge(source, generated(column("ID", "java.lang.Integer")));
        assertTrue(merged.contains("public java.lang.Integer getId() { return 17; }"));
        assertTrue(merged.contains("public void setId(java.lang.Integer id) { System.out.println(id); }"));
        compile(merged);
    }

    @Test void renamedFieldWithSameColumnDoesNotGenerateDuplicateMapping() {
        String source = generated(column("ID", "java.lang.Integer"))
                .replace(" id", " otherId").replace("return id", "return otherId").replace("this.id", "this.otherId");
        assertThrows(GenerationException.class, () -> merge(source, generated(column("ID", "java.lang.Integer"))));
    }

    @Test void refusesSyntaxErrorsWrongTargetAndImmutableEntities() {
        String source = generated(column("ID", "java.lang.Integer"));
        assertThrows(GenerationException.class, () -> merge(source.replace("return id;", "return ???;"), source));
        assertThrows(GenerationException.class, () -> merge(source.replace("package example;", "package wrong;"), source));
        assertThrows(GenerationException.class, () -> merge(source.replace("class Result", "class Other"), source));
        assertThrows(GenerationException.class, () -> merge(source.replace("@Entity", "@Entity(immutable = true)"), source));
    }

    @Test void keepsCustomInitializersAndAnnotationsAndBlocksRemovingThem() {
        String source = generated(column("ID", "java.lang.Integer"), column("NAME", "java.lang.String"))
                .replace("String name;", "String name = \"default\";");
        assertEquals(source, merge(source, source));
        assertThrows(GenerationException.class, () -> merge(source, generated(column("ID", "java.lang.Integer"))));
        String annotated = generated(column("ID", "java.lang.Integer"), column("NAME", "java.lang.String"))
                .replace("private String name;", "@Deprecated private String name;");
        assertThrows(GenerationException.class, () -> merge(annotated, generated(column("ID", "java.lang.Integer"))));
    }

    @Test void maintainsCrLfAndTabIndentation() throws Exception {
        String source = generated(column("ID", "java.lang.Integer"))
                .replace("    ", "\t").replace("\n", "\r\n");
        String merged = merge(source, generated(column("ID", "java.lang.Integer"), column("NAME", "java.lang.String")));
        assertFalse(merged.replace("\r\n", "").contains("\n"));
        assertTrue(merged.contains("\r\n\tprivate String name;"));
        compile(merged);
    }

    @Test void updatePlanRejectsDirtyOrChangedSourcesAndAcceptsOnlyThePreviewedVersion() {
        var plan = new SourceUpdatePlan("before", "after", 12);
        assertTrue(plan.hasChanges());
        assertDoesNotThrow(() -> plan.verifyCurrent("before", 12, false));
        assertThrows(GenerationException.class, () -> plan.verifyCurrent("before", 12, true));
        assertThrows(GenerationException.class, () -> plan.verifyCurrent("changed", 12, false));
        assertThrows(GenerationException.class, () -> plan.verifyCurrent("before", 13, false));
        assertFalse(new SourceUpdatePlan("same", "same", 12).hasChanges());
    }

    @Test void updatesOlderQualifiedPackageFieldsToPrivateAndImportsWhileKeepingManualCode() throws Exception {
        String before = """
                package example;
                /** Generated from SELECT result metadata. */
                @org.seasar.doma.Entity
                public class Result {
                    /** Amount comment.
                     * @domaGenerated
                     */
                    @org.seasar.doma.Column(name = "AMOUNT")
                    java.math.BigDecimal amount; // amount note
                    /** @domaGenerated */
                    public java.math.BigDecimal getAmount() { return amount; }
                    /** @domaGenerated */
                    public void setAmount(java.math.BigDecimal amount) { this.amount = amount; }
                    /** Handwritten method. */
                    public String display() { return amount == null ? "" : amount.toPlainString(); }
                }
                """;
        String proposed = generated(column("AMOUNT", "java.math.BigDecimal"), column("CREATED_AT", "java.time.LocalDateTime"));
        String merged = merge(before, proposed);
        assertTrue(merged.contains("import java.math.BigDecimal;"));
        assertTrue(merged.contains("import java.time.LocalDateTime;"));
        assertTrue(merged.contains("import org.seasar.doma.Column;"));
        assertTrue(merged.contains("import org.seasar.doma.Entity;"));
        assertTrue(merged.contains("private BigDecimal amount; // amount note"));
        assertTrue(merged.contains("public BigDecimal getAmount()"));
        assertTrue(merged.contains("public void setAmount(BigDecimal amount)"));
        assertTrue(merged.contains("/** Amount comment."));
        assertTrue(merged.contains("public String display() { return amount == null ? \"\" : amount.toPlainString(); }"));
        assertEquals(merged, merge(merged, proposed));
        compile(merged);
    }

    @Test void newGeneratedImportDoesNotShadowAnExistingManualImport() throws Exception {
        String before = generated(column("ID", "java.lang.Long"))
                .replace("package example;", "package example;\nimport java.util.Date; // manual import");
        String manual = "\n    @org.seasar.doma.Transient\n    private Date cacheDate; // handwritten\n";
        before = custom(before, manual);
        String proposed = generated(column("ID", "java.lang.Long"), column("DAY", "java.sql.Date"));
        String merged = merge(before, proposed);
        assertTrue(merged.contains("import java.util.Date; // manual import"));
        assertTrue(merged.contains(manual));
        assertTrue(merged.contains("private java.sql.Date day;"));
        assertFalse(merged.contains("import java.sql.Date;"));
        assertEquals(merged, merge(merged, proposed));
        compile(merged);
    }

    @Test void nestedManualTypesAndWildcardStaticReferencesKeepTheirMeaning() throws Exception {
        String before = generated(column("ID", "java.lang.Long"))
                .replace("package example;", "package example;\nimport java.util.*;");
        String manual = """

            static class LocalDateTime { /* handwritten nested type */ }
            public long parse(String text) { return Date.parse(text); }
            """;
        before = custom(before, manual);
        String proposed = generated(column("ID", "java.lang.Long"), column("DAY", "java.sql.Date"),
                column("CREATED_AT", "java.time.LocalDateTime"));
        String merged = merge(before, proposed);
        assertTrue(merged.contains(manual));
        assertTrue(merged.contains("private java.sql.Date day;"));
        assertTrue(merged.contains("private java.time.LocalDateTime createdAt;"));
        assertFalse(merged.contains("import java.sql.Date;"));
        assertFalse(merged.contains("import java.time.LocalDateTime;"));
        compile(merged);
    }

    @Test void samePackageDomainNamesStayShortAndUnchanged() {
        String source = generated(column("AMOUNT", "example.Amount"));
        assertTrue(source.contains("private Amount amount;"));
        assertFalse(source.contains("import example.Amount;"));
        assertEquals(source, merge(source, source));
    }

    @Test void generatedImportsDoNotConflictWithExistingStaticTypeImports() throws Exception {
        String before = generated(column("ID", "java.lang.Long"))
                .replace("package example;", "package example;\nimport static example.DateHolder.Date;")
                + "\nclass DateHolder { static class Date {} }\n";
        String proposed = generated(column("ID", "java.lang.Long"), column("DAY", "java.sql.Date"));
        String merged = merge(before, proposed);
        assertTrue(merged.contains("import static example.DateHolder.Date;"));
        assertFalse(merged.contains("import java.sql.Date;"));
        assertTrue(merged.contains("private java.sql.Date day;"));
        compile(merged);
    }

    @Test void changingATypedFieldAddsImportsAndKeepsItsCustomOverload() throws Exception {
        String manual = "\n    public void setId(String text) { System.out.println(text); }\n";
        String before = custom(generated(column("ID", "java.lang.Integer")), manual);
        String proposed = generated(column("ID", "java.math.BigDecimal"));
        String merged = merge(before, proposed);
        assertTrue(merged.contains("import java.math.BigDecimal;"));
        assertTrue(merged.contains("private BigDecimal id;"));
        assertTrue(merged.contains("setId(BigDecimal id)"));
        assertTrue(merged.contains(manual));
        assertEquals(merged, merge(merged, proposed));
        compile(merged);
    }

    @Test void multipleWildcardImportsResolveDomaAndUnchangedTypesWithoutTouchingCustomGetter() throws Exception {
        String before = generated(column("SALARY", "java.math.BigDecimal"))
                .replace("import org.seasar.doma.Column;\nimport org.seasar.doma.Entity;", "import org.seasar.doma.*;")
                .replace("import java.math.BigDecimal;", "import java.math.*;\nimport java.time.*;")
                .replace("return salary;", "return salary == null ? BigDecimal.ZERO : salary;");
        String merged = merge(before, generated(column("SALARY", "java.math.BigDecimal")));
        assertTrue(merged.contains("return salary == null ? BigDecimal.ZERO : salary;"));
        assertTrue(merged.contains("private BigDecimal salary;"));
        assertEquals(merged, merge(merged, generated(column("SALARY", "java.math.BigDecimal"))));
        compile(merged);
    }

    @Test void ambiguousWildcardTypeIsRejectedInsteadOfGuessed() {
        String before = generated(column("DAY", "java.sql.Date"))
                .replace("import java.sql.Date;", "import java.sql.*;\nimport java.util.*;");
        var error = assertThrows(GenerationException.class, () -> merge(before, generated(column("DAY", "java.sql.Date"))));
        assertTrue(error.getMessage().contains("Ambiguous Java type 'Date'"));
        assertTrue(error.getMessage().contains("explicit import"));
    }

    @Test void samePackageTypesShadowJavaLangWhenResolvingExistingSource() {
        String before = generated(column("VALUE", "example.String"))
                .replace("return value;", "return value == null ? new String() : value;") + "\nclass String {}\n";
        assertThrows(GenerationException.class, () -> merge(before, generated(column("VALUE", "java.lang.String"))));
        String external = before.substring(0, before.lastIndexOf("class String"));
        var lookup = (java.util.function.Predicate<String>) name -> name.equals("example.String");
        String desired = new EntityGenerator().generate("example", "Result", List.of(column("VALUE", "java.lang.String")), lookup);
        assertThrows(GenerationException.class, () -> new EntityMerger().merge(external, desired, Map.of(), lookup));
    }

    @Test void nestedTypeInAnotherTopLevelClassDoesNotShadowAnEntityFieldType() throws Exception {
        String before = generated(column("VALUE", "java.lang.String"))
                .replace("return value;", "return value == null ? \"\" : value;")
                + "\nclass Helper { static class String {} }\n";
        String merged = merge(before, generated(column("VALUE", "java.lang.String")));
        assertTrue(merged.contains("return value == null ? \"\" : value;"));
        compile(merged);
    }

    @Test void bomAndCrLfAreKeptWhileUpdatingTheClass() throws Exception {
        String before = "\uFEFF" + generated(column("ID", "java.lang.Integer")).replace("\n", "\r\n");
        String merged = merge(before, generated(column("ID", "java.lang.Long")));
        assertTrue(merged.startsWith("\uFEFFpackage example;"));
        assertEquals(1, merged.chars().filter(c -> c == '\uFEFF').count());
        assertTrue(merged.contains("private Long id;"));
        assertFalse(merged.replace("\r\n", "").contains("\n"));
        compile(merged.substring(1));
    }

    @Test void generatedImportsAreRemovedAfterTypeChangeOrColumnRemovalButCommentsStay() throws Exception {
        String before = generated(column("AMOUNT", "java.math.BigDecimal"), column("ID", "java.lang.Integer"))
                .replace("import java.math.BigDecimal;", "import java.math.BigDecimal; // keep import note\nimport java.util.Locale;");
        String changed = merge(before, generated(column("AMOUNT", "java.lang.Long"), column("ID", "java.lang.Integer")));
        assertFalse(changed.contains("import java.math.BigDecimal;"));
        assertTrue(changed.contains("// keep import note"));
        assertTrue(changed.contains("import java.util.Locale;"));
        String removed = merge(before, generated(column("ID", "java.lang.Integer")));
        assertFalse(removed.contains("import java.math.BigDecimal;"));
        assertEquals(removed, merge(removed, generated(column("ID", "java.lang.Integer"))));
        compile(changed);
        compile(removed);
    }

    @Test void importsUsedByManualCodeAndJavadocAreRetained() throws Exception {
        String before = custom(generated(column("AMOUNT", "java.math.BigDecimal")),
                "\n    /** See {@link BigDecimal}. */\n    public void help() {}\n");
        String merged = merge(before, generated(column("AMOUNT", "java.lang.Long")));
        assertTrue(merged.contains("import java.math.BigDecimal;"));
        assertTrue(merged.contains("/** See {@link BigDecimal}. */"));
        compile(merged);
        String helper = "\n    public BigDecimal constant() { return BigDecimal.ZERO; }\n";
        before = custom(generated(column("AMOUNT", "java.math.BigDecimal")), helper);
        merged = merge(before, generated(column("AMOUNT", "java.lang.Long")));
        assertTrue(merged.contains("import java.math.BigDecimal;"));
        assertTrue(merged.contains(helper));
        compile(merged);
    }

    private void compile(String source) throws Exception {
        Path file = directory.resolve("Result.java");
        Files.writeString(file, source);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var options = List.of("--release", "8", "-classpath", System.getProperty("java.class.path"), "-d", directory.toString(),
                    "-s", directory.toString(), "-processor", "org.seasar.doma.internal.apt.processor.EntityProcessor");
            assertEquals(Boolean.TRUE, compiler.getTask(null, manager, diagnostics, options, null,
                    manager.getJavaFileObjects(file.toFile())).call(), diagnostics.getDiagnostics().toString());
        }
    }
}
