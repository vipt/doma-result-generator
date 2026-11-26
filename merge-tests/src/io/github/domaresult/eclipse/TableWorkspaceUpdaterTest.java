/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.eclipse;

import static org.junit.Assert.*;
import io.github.domaresult.core.GenerationException;
import io.github.domaresult.core.TableGenerator.Artifact;
import io.github.domaresult.core.TableGenerator.Kind;
import io.github.domaresult.eclipse.TableWorkspaceUpdater.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.eclipse.core.filebuffers.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.JavaCore;
import org.junit.*;

public class TableWorkspaceUpdaterTest {
    private IProject project;
    private Path state;
    private TableWorkspaceUpdater updater;
    @Before public void setup() throws Exception {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("table-gen-" + UUID.randomUUID());
        project.create(null); project.open(null); project.setDefaultCharset("UTF-8", null);
        var description = project.getDescription(); description.setNatureIds(new String[]{JavaCore.NATURE_ID}); project.setDescription(description, null);
        state = Files.createTempDirectory("doma-table-baseline-"); updater = new TableWorkspaceUpdater(state);
    }
    @After public void cleanup() throws Exception {
        if (project != null) project.delete(true, true, null);
        if (state != null) try (var files = Files.walk(state)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
    private Artifact java(String source) { return new Artifact(Kind.ENTITY, "example/Employee.java", source); }
    private IFile file(String name, String source) throws Exception {
        IFile file = project.getFile(name);
        if (file.getParent() instanceof IFolder folder && !folder.exists()) parents(folder);
        file.create(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), false, null); return file;
    }
    private void parents(IFolder folder) throws Exception {
        if (folder.getParent() instanceof IFolder parent && !parent.exists()) parents(parent);
        folder.create(false, true, null);
    }
    private String read(IFile file) throws Exception { try (var stream = file.getContents()) { return new String(stream.readAllBytes(), java.nio.charset.Charset.forName(file.getCharset())); } }
    private void write(IFile file, String source) throws Exception { file.setContents(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), IResource.KEEP_HISTORY, null); }
    private Plan plan(IFile file, Artifact generated, Action action) throws Exception { return updater.plan(generated, updater.snapshot(file), action); }
    private static String base() { return "package example;\npublic class Employee {\n    private String name;\n\n    public String getName() { return name; }\n}\n"; }

    @Test public void previewAndCancelNeverCreateFilesOrBaselines() throws Exception {
        IFile file = project.getFile("src/example/Employee.java"); var plan = plan(file, java(base()), Action.MERGE);
        assertTrue(plan.changed()); assertFalse(file.exists());
        try (var saved = Files.list(state)) { assertEquals(0L, saved.count()); }
        var monitor = new NullProgressMonitor(); monitor.setCanceled(true);
        assertThrows(org.eclipse.core.runtime.OperationCanceledException.class, () -> updater.apply(List.of(plan), monitor)); assertFalse(file.exists());
    }
    @Test public void writesNewFilesAndRetainsLocalHistoryWhenOverwriting() throws Exception {
        IFile entity = project.getFile("src/example/Employee.java"), sql = project.getFile("resources/META-INF/example/EmployeeDao/selectById.sql");
        var query = new Artifact(Kind.SQL, "META-INF/example/EmployeeDao/selectById.sql", "select * from employee where id=/*id*/0\n");
        updater.apply(List.of(plan(entity, java(base()), Action.MERGE), plan(sql, query, Action.MERGE)), null);
        assertEquals(base(), read(entity)); assertEquals(query.source(), read(sql));
        String custom = base().replace("private String name;", "// manual comment\n    private String name;"); write(entity, custom);
        var overwrite = plan(entity, java(base()), Action.OVERWRITE); assertEquals(custom, read(entity)); assertEquals(base(), overwrite.after());
        updater.apply(List.of(overwrite), null); assertEquals(base(), read(entity)); assertTrue(entity.getHistory(null).length > 0);
    }
    @Test public void firstJavaMergeRetainsMembersAndCommentsAndAddsMissingMembers() throws Exception {
        String old = base().replace("private String name;", "/** Handwritten comment. */\n    private String name;")
                .replace("\n}", "\n    // custom code\n    public String label() { return name; }\n}");
        IFile entity = file("src/example/Employee.java", old);
        String generated = base().replace("private String name;", "private String name;\n    private Long id;")
                .replace("\n}", "\n    public Long getId() { return id; }\n}");
        var merged = plan(entity, java(generated), Action.MERGE);
        for (String text : List.of("Handwritten comment.", "// custom code", "label()", "private Long id;")) assertTrue(merged.after().contains(text));
        assertEquals(old, read(entity)); updater.apply(List.of(merged), null); assertEquals(merged.after(), read(entity));
    }
    @Test public void newGeneratedAndHandwrittenMethodsAtClassEndMergeWithoutConflicting() throws Exception {
        IFile entity = project.getFile("src/example/Employee.java"); updater.apply(List.of(plan(entity, java(base()), Action.MERGE)), null);
        String custom = base().replace("\n}", "\n    /** Custom. */\n    public String label() { return name; }\n}"); write(entity, custom);
        String generated = base().replace("private String name;", "private String name;\n    private Long id;")
                .replace("\n}", "\n    public Long getId() { return id; }\n}");
        Plan merged = plan(entity, java(generated), Action.MERGE);
        for (String text : List.of("Custom.", "getId()", "label()")) assertTrue(merged.after().contains(text));
        updater.apply(List.of(merged), null); assertFalse(plan(entity, java(generated), Action.MERGE).changed());
    }
    @Test public void conflictingChangesToManagedMemberNeverWrite() throws Exception {
        IFile entity = project.getFile("src/example/Employee.java"); updater.apply(List.of(plan(entity, java(base()), Action.MERGE)), null);
        String custom = base().replace("private String name;", "private Integer name;"); write(entity, custom);
        assertThrows(GenerationException.class, () -> plan(entity, java(base().replace("private String name;", "private Long name;")), Action.MERGE));
        assertEquals(custom, read(entity));
    }
    @Test public void sqlMergesDisjointEditsAndBlocksOverlappingChanges() throws Exception {
        IFile sql = project.getFile("resources/META-INF/example/Dao/find.sql");
        String baseline = "select\n  id,\n  name\nfrom employee\nwhere id=/*id*/0\norder by id\n";
        var artifact = new Artifact(Kind.SQL, "META-INF/example/Dao/find.sql", baseline); updater.apply(List.of(plan(sql, artifact, Action.MERGE)), null);
        write(sql, baseline.replace("order by id", "-- handwritten order\norder by name"));
        var next = new Artifact(Kind.SQL, artifact.path(), baseline.replace("  name", "  name,\n  active"));
        var merged = plan(sql, next, Action.MERGE);
        assertTrue(merged.after().contains("active")); assertTrue(merged.after().contains("-- handwritten order\norder by name"));
        updater.apply(List.of(merged), null); write(sql, merged.after().replace("active", "custom_column"));
        assertThrows(GenerationException.class, () -> plan(sql, new Artifact(Kind.SQL, artifact.path(), next.source().replace("active", "generated_column")), Action.MERGE));
    }
    @Test public void untrackedSqlIsKeptAndCanBeExplicitlyOverwritten() throws Exception {
        IFile sql = file("resources/find.sql", "-- custom SQL\nselect id from employee\n");
        var generated = new Artifact(Kind.SQL, "find.sql", "select id,name from employee\n");
        var merge = plan(sql, generated, Action.MERGE); assertFalse(merge.changed()); assertTrue(merge.note().contains("retained")); updater.apply(List.of(merge), null);
        try (var saved = Files.list(state)) { assertEquals(0L, saved.count()); }
        var overwrite = plan(sql, generated, Action.OVERWRITE); assertTrue(overwrite.changed()); updater.apply(List.of(overwrite), null); assertEquals(generated.source(), read(sql));
    }
    @Test public void keepDoesNotChangeContentOrAdvanceBaseline() throws Exception {
        IFile entity = file("src/example/Employee.java", base()); var keep = plan(entity, java(base().replace("String", "Long")), Action.KEEP);
        assertFalse(keep.changed()); updater.apply(List.of(keep), null); assertEquals(base(), read(entity));
        try (var saved = Files.list(state)) { assertEquals(0L, saved.count()); }
    }
    @Test public void staleFilePreventsEveryWriteInBatch() throws Exception {
        IFile first = file("resources/one.sql", "select 1\n"), second = file("resources/two.sql", "select 2\n");
        var one = plan(first, new Artifact(Kind.SQL,"one.sql","select 10\n"), Action.OVERWRITE);
        var two = plan(second, new Artifact(Kind.SQL,"two.sql","select 20\n"), Action.OVERWRITE); write(second, "select 200\n");
        assertThrows(GenerationException.class, () -> updater.apply(List.of(one, two), null));
        assertEquals("select 1\n", read(first)); assertEquals("select 200\n", read(second));
    }
    @Test public void newlyCreatedTargetAfterPreviewIsPreserved() throws Exception {
        IFile file = project.getFile("resources/new.sql"); var preview = plan(file, new Artifact(Kind.SQL,"new.sql","select 1\n"), Action.MERGE);
        file("resources/new.sql", "-- another process\nselect 2\n");
        assertThrows(GenerationException.class, () -> updater.apply(List.of(preview), null)); assertEquals("-- another process\nselect 2\n", read(file));
    }
    @Test public void dirtyEditorPreventsSnapshotAndApply() throws Exception {
        IFile file = file("resources/find.sql", "select 1\n"); var preview = plan(file, new Artifact(Kind.SQL,"find.sql","select 2\n"), Action.OVERWRITE);
        var manager = FileBuffers.getTextFileBufferManager(); manager.connect(file.getFullPath(), LocationKind.IFILE, null);
        try {
            manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE).getDocument().set("select 3\n");
            assertThrows(GenerationException.class, () -> updater.snapshot(file)); assertThrows(GenerationException.class, () -> updater.apply(List.of(preview), null));
            assertEquals("select 1\n", read(file));
        } finally { manager.disconnect(file.getFullPath(), LocationKind.IFILE, null); }
    }
    @Test public void overwriteRetainsBomLineEndingsAndCharset() throws Exception {
        IFile file = file("resources/find.sql", "\uFEFF-- old\r\nselect 1\r\n");
        var overwrite = plan(file, new Artifact(Kind.SQL,"find.sql","-- new\nselect 2\n"), Action.OVERWRITE);
        assertEquals("\uFEFF-- new\r\nselect 2\r\n", overwrite.after()); updater.apply(List.of(overwrite), null);
        assertEquals(overwrite.after(), read(file)); assertEquals("UTF-8", file.getCharset());
    }
    @Test public void addsMissingTableAnnotationWhilePreservingExistingEntityAnnotationAndJavadoc() throws Exception {
        String before = "package example;\nimport org.seasar.doma.Entity;\n/** Handwritten. */\n@Entity\npublic class Employee {\n private Long id;\n}\n";
        String generated = "package example;\nimport org.seasar.doma.Entity;\nimport org.seasar.doma.Table;\n@Entity\n@Table(name = \"EMPLOYEE\")\npublic class Employee {\n private Long id;\n}\n";
        var file = file("src/example/Employee.java", before);
        var merge = plan(file, java(generated), Action.MERGE);
        assertTrue(merge.after().contains("Handwritten.")); assertTrue(merge.after().contains("@Table(name = \"EMPLOYEE\")"));
        assertEquals(1, merge.after().split("@Entity", -1).length - 1); assertEquals(before, read(file));
        TableFileMerger.parse(merge.after());
    }
    @Test public void removalOfFieldReferencedByCustomMethodIsBlocked() throws Exception {
        IFile entity = project.getFile("src/example/Employee.java"); updater.apply(List.of(plan(entity, java(base()), Action.MERGE)), null);
        write(entity, base().replace("\n}", "\n public String customLabel() { return name; }\n}"));
        String next = "package example;\npublic class Employee {\n}\n";
        assertTrue(assertThrows(GenerationException.class, () -> plan(entity, java(next), Action.MERGE)).getMessage().contains("referenced"));
        assertTrue(read(entity).contains("customLabel"));
    }
    @Test public void failureOnLaterFileRestoresEarlierFileAndRemovesCreatedFolders() throws Exception {
        IFile existing = file("resources/one.sql", "select 1\n");
        IFile blocked = project.getFile("blocked/two.sql"); file("blocked", "file occupying a directory name");
        var one = plan(existing, new Artifact(Kind.SQL,"one.sql","select 10\n"), Action.OVERWRITE);
        var two = plan(blocked, new Artifact(Kind.SQL,"two.sql","select 20\n"), Action.OVERWRITE);
        assertThrows(org.eclipse.core.runtime.CoreException.class, () -> updater.apply(List.of(one, two), null));
        assertEquals("select 1\n", read(existing)); assertFalse(blocked.exists());
    }
}
