/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.eclipse;

import static org.junit.Assert.*;
import io.github.domaresult.core.EntityGenerator;
import io.github.domaresult.core.GenerationException;
import io.github.domaresult.core.ResultColumn;
import io.github.domaresult.core.SourceUpdatePlan;
import io.github.domaresult.core.JdbcErrorDetails;
import io.github.domaresult.merge.EntityMerger;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.lang.reflect.Proxy;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.IJavaModelStatusConstants;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.*;

/** Runs on real Eclipse files, file buffers, local history and compiler bindings. */
public class WorkspaceClassUpdaterTest {
    private static IVMInstall vm;
    private IProject project;
    private IJavaProject javaProject;
    private ICompilationUnit target;
    private final WorkspaceClassUpdater updater = new WorkspaceClassUpdater();

    @BeforeClass public static void registerVm() {
        var type = JavaRuntime.getVMInstallType("org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType");
        vm = type.createVMInstall("doma-merge-test-vm");
        vm.setName("Merge test JRE");
        vm.setInstallLocation(new File(System.getProperty("java.home")));
    }

    @AfterClass public static void unregisterVm() {
        if (vm != null) vm.getVMInstallType().disposeVMInstall(vm.getId());
    }

    @Before public void setUp() throws Exception {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("merge-" + UUID.randomUUID());
        project.create(null);
        project.open(null);
        var description = project.getDescription();
        description.setNatureIds(new String[] { JavaCore.NATURE_ID });
        project.setDescription(description, null);
        project.setDefaultCharset("UTF-8", null);
        project.getFolder("src").create(true, true, null);
        project.getFolder("bin").create(true, true, null);
        javaProject = JavaCore.create(project);
        var options = new HashMap<String, String>();
        JavaCore.setComplianceOptions(JavaCore.VERSION_17, options);
        javaProject.setOptions(options);
        javaProject.setRawClasspath(new IClasspathEntry[] {
                JavaCore.newSourceEntry(project.getFolder("src").getFullPath()),
                JavaCore.newContainerEntry(JavaRuntime.newJREContainerPath(vm))
        }, project.getFolder("bin").getFullPath(), null);
        unit("org.seasar.doma", "Entity.java", "package org.seasar.doma; public @interface Entity {}");
        unit("org.seasar.doma", "Column.java", "package org.seasar.doma; public @interface Column { String name(); }");
        target = unit("example", "Result.java", generated("java.lang.Integer"));
    }

    @After public void tearDown() throws Exception {
        if (project != null && project.exists()) project.delete(true, true, null);
    }

    @Test public void appliesReviewedChangesAndKeepsLocalHistoryAndManualComments() throws Exception {
        String old = generated("java.lang.Integer");
        int close = old.lastIndexOf('}');
        String manual = "\n    /** 手動コメント */\n    public String display() { return String.valueOf(id); }\n";
        write(old.substring(0, close) + manual + old.substring(close));
        var saved = updater.snapshot(target);
        String after = new EntityMerger().merge(saved.source(), generated("java.lang.Long")).source();
        updater.validate(target, after, null);
        assertEquals(saved.source(), updater.snapshot(target).source());
        updater.apply(target, new SourceUpdatePlan(saved.source(), after, saved.modificationStamp()));
        assertEquals(after, updater.snapshot(target).source());
        assertTrue(after.contains(manual));
        assertTrue("The previous saved class should be in local history", file().getHistory(null).length > 0);
    }

    @Test public void savedChangeAfterPreviewIsNeverOverwritten() throws Exception {
        var saved = updater.snapshot(target);
        var plan = new SourceUpdatePlan(saved.source(), generated("java.lang.Long"), saved.modificationStamp());
        String edited = saved.source() + "\n// Saved after preview\n";
        write(edited);
        assertThrows(GenerationException.class, () -> updater.apply(target, plan));
        assertEquals(edited, updater.snapshot(target).source());
    }

    @Test public void dirtyEditorBufferBlocksSnapshotsAndApplyingOldPreview() throws Exception {
        var saved = updater.snapshot(target);
        var plan = new SourceUpdatePlan(saved.source(), generated("java.lang.Long"), saved.modificationStamp());
        var manager = FileBuffers.getTextFileBufferManager();
        manager.connect(file().getFullPath(), LocationKind.IFILE, null);
        try {
            var buffer = manager.getTextFileBuffer(file().getFullPath(), LocationKind.IFILE);
            buffer.getDocument().set(saved.source() + "\n// Unsaved user edit\n");
            assertTrue(buffer.isDirty());
            assertThrows(GenerationException.class, () -> updater.snapshot(target));
            assertThrows(GenerationException.class, () -> updater.apply(target, plan));
            try (var in = file().getContents()) {
                assertEquals(saved.source(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertTrue(buffer.getDocument().get().contains("Unsaved user edit"));
        } finally { manager.disconnect(file().getFullPath(), LocationKind.IFILE, null); }
    }

    @Test public void privateWorkingCopyRejectsTypeErrorsInManualMethodsWithoutChangingTheFile() throws Exception {
        String source = generated("java.lang.Integer");
        int close = source.lastIndexOf('}');
        source = source.substring(0, close) + "\n    public Integer manualValue() { return id; }\n" + source.substring(close);
        write(source);
        var saved = updater.snapshot(target);
        String after = new EntityMerger().merge(saved.source(), generated("java.lang.Long")).source();
        var error = assertThrows(GenerationException.class, () -> updater.validate(target, after, null));
        assertTrue(error.getMessage(), error.getMessage().contains("Java error"));
        assertEquals(saved.source(), updater.snapshot(target).source());
        assertFalse(target.hasUnsavedChanges());
    }

    @Test public void unsupportedCharacterEncodingStopsBeforeWriting() throws Exception {
        file().setCharset("US-ASCII", null);
        var saved = updater.snapshot(target);
        String after = saved.source() + "\n// 日本語\n";
        assertThrows(GenerationException.class, () -> updater.apply(target,
                new SourceUpdatePlan(saved.source(), after, saved.modificationStamp())));
        assertEquals(saved.source(), updater.snapshot(target).source());
    }

    @Test public void deletedTargetStopsWithoutRecreatingIt() throws Exception {
        var saved = updater.snapshot(target);
        var plan = new SourceUpdatePlan(saved.source(), generated("java.lang.Long"), saved.modificationStamp());
        file().delete(true, null);
        assertThrows(GenerationException.class, () -> updater.apply(target, plan));
        assertFalse(file().exists());
    }

    @Test public void utf8BomIsPreservedThroughPreviewValidationAndWrite() throws Exception {
        write("\uFEFF" + generated("java.lang.Integer"));
        var saved = updater.snapshot(target);
        assertTrue(saved.source().startsWith("\uFEFF"));
        var stages = new ArrayList<JdbcErrorDetails.Stage>();
        var proposed = updater.preview(target, saved.source(), generated("java.lang.Long"), null, stages::add);
        assertEquals(List.of(JdbcErrorDetails.Stage.MERGING_CLASS, JdbcErrorDetails.Stage.VALIDATING_CLASS), stages);
        updater.apply(target, new SourceUpdatePlan(saved.source(), proposed.source(), saved.modificationStamp()));
        try (var stream = file().getContents()) {
            byte[] bytes = stream.readAllBytes();
            assertArrayEquals(new byte[] { (byte) 0xef, (byte) 0xbb, (byte) 0xbf }, java.util.Arrays.copyOf(bytes, 3));
            String after = new String(bytes, StandardCharsets.UTF_8);
            assertTrue(after.contains("private Long id;"));
            assertEquals(1, after.chars().filter(c -> c == '\uFEFF').count());
        }
    }

    @Test public void projectLookupResolvesWildcardDomainTypesAndJavaLangShadowing() throws Exception {
        unit("domain", "Amount.java", "package domain; public class Amount {}");
        String before = new EntityGenerator().generate("example", "Result", List.of(new ResultColumn("AMOUNT", "domain.Amount")))
                .replace("import domain.Amount;", "import domain.*;\nimport java.time.*;")
                .replace("return amount;", "return amount == null ? new Amount() : amount;");
        write(before);
        var result = updater.preview(target, before, new EntityGenerator().generate("example", "Result", List.of(new ResultColumn("AMOUNT", "domain.Amount"))), null, stage -> {});
        assertTrue(result.source().contains("return amount == null ? new Amount() : amount;"));
        assertTrue(result.source().contains("private Amount amount;"));
        unit("example", "String.java", "package example; public class String {}");
        String generated = updater.generate(target, "example", "Result", List.of(new ResultColumn("NAME", "java.lang.String")));
        assertTrue(generated.contains("private java.lang.String name;"));
        updater.validate(target, generated, null);
    }

    @Test public void validationJavaModelFailureHasItsOwnStageAndGuidance() throws Exception {
        var failure = new JavaModelException(new Exception("private details"), IJavaModelStatusConstants.ELEMENT_DOES_NOT_EXIST);
        ICompilationUnit broken = (ICompilationUnit) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { ICompilationUnit.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("getJavaProject")) return javaProject;
                    if (method.getName().equals("getWorkingCopy")) throw failure;
                    throw new UnsupportedOperationException(method.getName());
                });
        var stages = new ArrayList<JdbcErrorDetails.Stage>();
        assertSame(failure, assertThrows(JavaModelException.class, () -> updater.preview(broken, generated("java.lang.Integer"), generated("java.lang.Long"), null, stages::add)));
        assertEquals(JdbcErrorDetails.Stage.VALIDATING_CLASS, stages.get(stages.size() - 1));
        var info = JdbcErrorDetails.describe(failure, stages.get(stages.size() - 1), false);
        assertTrue(info.summary().contains("validating the proposed result class"));
        assertFalse(info.details().contains("driver JAR"));
        assertFalse(info.details().contains("private details"));
    }

    private String generated(String type) {
        return new EntityGenerator().generate("example", "Result", List.of(new ResultColumn("ID", type)));
    }

    private ICompilationUnit unit(String pkg, String name, String source) throws Exception {
        return javaProject.getPackageFragmentRoot(project.getFolder("src")).createPackageFragment(pkg, true, null)
                .createCompilationUnit(name, source, false, null);
    }

    private IFile file() { return (IFile) target.getResource(); }

    private void write(String source) throws Exception {
        file().setContents(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), true, false, null);
    }
}
