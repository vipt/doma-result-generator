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
        assertTrue(merged.contains("java.lang.String name;"));
        assertTrue(merged.contains("getName()"));
        compile(merged);
    }

    @Test void updatesFieldAndSimpleAccessorTypesWhileKeepingAllComments() throws Exception {
        String source = generated(column("ID", "java.lang.Integer"))
                .replace("java.lang.Integer id;", "java.lang.Integer /* field note */ id; // trailing field note")
                .replace("return id;", "// getter explanation\n        return /* inline return */ id;")
                .replace("this.id = id;", "/* setter explanation */ this.id = id;");
        String merged = merge(source, generated(column("ID", "java.lang.Long")));
        for (String comment : List.of("/* field note */", "// trailing field note", "// getter explanation", "/* inline return */", "/* setter explanation */")) {
            assertTrue(merged.contains(comment), comment);
        }
        assertTrue(merged.contains("java.lang.Long /* field note */ id;"));
        assertTrue(merged.contains("public java.lang.Long getId()"));
        assertTrue(merged.contains("setId(java.lang.Long id)"));
        compile(merged);
    }

    @Test void preservesCommentsInsideQualifiedTypeWhenReplacingTheType() throws Exception {
        String source = generated(column("ID", "java.lang.Integer"))
                .replace("java.lang.Integer id;", "java./* special type note */lang.Integer id;");
        String merged = merge(source, generated(column("ID", "java.lang.Long")));
        assertTrue(merged.contains("/* special type note */ java.lang.Long id;"));
        compile(merged);
    }

    @Test void removesOwnedObsoleteMembersAndKeepsTheirUserComments() throws Exception {
        String source = generated(column("ID", "java.lang.Integer"), column("OLD_VALUE", "java.lang.String"))
                .replace("java.lang.String oldValue;", "java.lang.String oldValue; // keep trailing old note")
                .replace("return oldValue;", "// keep getter note\n        return oldValue;")
                .replace("this.oldValue = oldValue;", "/* keep setter note */ this.oldValue = oldValue;")
                .replace("@org.seasar.doma.Column(name = \"OLD_VALUE\")", "@org.seasar.doma.Column(name = \"OLD_VALUE\") /* keep column note */");
        source = custom(source, "\n    /** Handwritten helper. */\n    public int answer() { return 42; }\n");
        String merged = merge(source, generated(column("ID", "java.lang.Integer")));
        assertFalse(merged.contains("getOldValue()"));
        assertFalse(merged.contains("setOldValue("));
        assertFalse(merged.contains("java.lang.String oldValue;"));
        for (String note : List.of("// keep trailing old note", "// keep getter note", "/* keep setter note */", "/* keep column note */", "/** Handwritten helper. */")) {
            assertTrue(merged.contains(note), note);
        }
        assertEquals(merged, merge(merged, generated(column("ID", "java.lang.Integer"))));
        compile(merged);
    }

    @Test void preservesUserJavadocOnRemovedGeneratedMembersAndRemovesOnlyOwnershipTag() throws Exception {
        String source = generated(column("ID", "java.lang.Integer"), column("OLD", "java.lang.String"))
                .replace("/** @domaGenerated */\n    @org.seasar.doma.Column(name = \"OLD\")",
                        "/** User note about old field.\n     * @domaGenerated\n     */\n    @org.seasar.doma.Column(name = \"OLD\")");
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
                .replace("@org.seasar.doma.Entity", "@Deprecated\n@org.seasar.doma.Entity");
        String constructors = "\n    public Result() {}\n    /** Custom constructor. */\n    public Result(Integer value) { this.id = value; }\n";
        source = custom(source, constructors) + "\nclass HandwrittenCompanion { /* keep */ }\n";
        String merged = merge(source, generated(column("ID", "java.lang.Integer"), column("NAME", "java.lang.String")));
        assertTrue(merged.contains(constructors));
        assertTrue(merged.contains("@Deprecated\n@org.seasar.doma.Entity"));
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
        assertTrue(merged.contains("setId(java.lang.Long id)"));
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
                .replace("@org.seasar.doma.Column(name = \"ID\")", "/** Customer identifier. */\n    @org.seasar.doma.Column(name = \"ID\")");
        String helper = "\n    // manual\n    public String describe() { return \"kept\"; }\n";
        source = custom(source, helper);
        var result = new EntityMerger().merge(source, generated(column("ID", "java.lang.Long")));
        assertTrue(result.source().contains("Customer identifier."));
        assertTrue(result.source().contains(helper));
        assertTrue(result.source().contains("java.lang.String old;"));
        assertTrue(result.source().contains("java.lang.Long id;"));
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
                .replace("/** @domaGenerated */\n    @org.seasar.doma.Column", "/** Handwritten field now. */\n    @org.seasar.doma.Column");
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
        assertThrows(GenerationException.class, () -> merge(source.replace("@org.seasar.doma.Entity", "@org.seasar.doma.Entity(immutable = true)"), source));
    }

    @Test void keepsCustomInitializersAndAnnotationsAndBlocksRemovingThem() {
        String source = generated(column("ID", "java.lang.Integer"), column("NAME", "java.lang.String"))
                .replace("java.lang.String name;", "java.lang.String name = \"default\";");
        assertEquals(source, merge(source, source));
        assertThrows(GenerationException.class, () -> merge(source, generated(column("ID", "java.lang.Integer"))));
        String annotated = generated(column("ID", "java.lang.Integer"), column("NAME", "java.lang.String"))
                .replace("java.lang.String name;", "@Deprecated java.lang.String name;");
        assertThrows(GenerationException.class, () -> merge(annotated, generated(column("ID", "java.lang.Integer"))));
    }

    @Test void maintainsCrLfAndTabIndentation() throws Exception {
        String source = generated(column("ID", "java.lang.Integer"))
                .replace("    ", "\t").replace("\n", "\r\n");
        String merged = merge(source, generated(column("ID", "java.lang.Integer"), column("NAME", "java.lang.String")));
        assertFalse(merged.replace("\r\n", "").contains("\n"));
        assertTrue(merged.contains("\r\n\tjava.lang.String name;"));
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
