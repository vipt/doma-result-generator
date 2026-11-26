/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import static org.junit.Assert.*;
import io.github.domaresult.core.GenerationException;
import io.github.domaresult.core.TableGenerator.*;
import io.github.domaresult.eclipse.TableWorkspaceUpdater;
import io.github.domaresult.eclipse.TableWorkspaceUpdater.Action;
import io.github.domaresult.ui.ProposedFilesModel.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.compare.structuremergeviewer.*;
import org.eclipse.core.filebuffers.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.JavaCore;
import org.junit.*;

/** Selection is tested through real comparison trees, workspace writes and baseline storage. */
public class ProposedFilesModelTest {
    private IProject project;
    private IFolder javaRoot, sqlRoot;
    private Path state;
    private TableWorkspaceUpdater updater;
    @Before public void setup() throws Exception {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("checked-files-" + UUID.randomUUID()); project.create(null); project.open(null);
        var description = project.getDescription(); description.setNatureIds(new String[]{JavaCore.NATURE_ID}); project.setDescription(description,null);
        project.setDefaultCharset("UTF-8",null);
        javaRoot = project.getFolder("src"); javaRoot.create(false,true,null);
        sqlRoot = project.getFolder("resources"); sqlRoot.create(false,true,null);
        state = Files.createTempDirectory("checked-baselines-"); updater = new TableWorkspaceUpdater(state);
    }
    @After public void cleanup() throws Exception {
        if (project != null) project.delete(true,true,null);
        if (state != null) try (var paths = Files.walk(state)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
    private Destination entity(String name, boolean existing) throws Exception {
        String path = "example/entity/" + name + ".java";
        String source = "package example.entity;\npublic class " + name + " {\n    private Long id;\n}\n";
        IFile file = javaRoot.getFile(path);
        if (existing) create(file, source.replace("    private Long id;\n", ""));
        return new Destination(new Artifact(Kind.ENTITY,path,source),file,javaRoot);
    }
    private Destination sql(String name, boolean existing) throws Exception {
        String path = "META-INF/example/dao/EmployeeDao/" + name + ".sql";
        IFile file = sqlRoot.getFile(path);
        if (existing) create(file,"-- manual query\nselect id from employee\n");
        return new Destination(new Artifact(Kind.SQL,path,"select id,name from employee\n"),file,sqlRoot);
    }
    private void create(IFile file, String source) throws Exception { parents(file.getParent()); file.create(bytes(source),false,null); }
    private void parents(IContainer parent) throws Exception {
        if (parent.exists()) return; parents(parent.getParent()); ((IFolder)parent).create(false,true,null);
    }
    private ByteArrayInputStream bytes(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    private void write(IFile file, String source) throws Exception { file.setContents(bytes(source),IResource.KEEP_HISTORY,null); }
    private String read(IFile file) throws Exception { try (var stream = file.getContents()) { return new String(stream.readAllBytes(),StandardCharsets.UTF_8); } }
    private ProposedFilesModel model(Destination... files) { return new ProposedFilesModel(updater,List.of(files)); }
    private Node leaf(ProposedFilesModel model, Destination destination) {
        return nodes(model.roots()).stream().filter(n -> n.entry != null && n.entry.destination.equals(destination)).findFirst().orElseThrow();
    }
    private List<Node> nodes(List<Node> roots) {
        List<Node> all = new ArrayList<>(); for (Node node : roots) { all.add(node); all.addAll(nodes(node.children())); } return all;
    }
    private long baselineCount() throws Exception { try (var files = Files.list(state)) { return files.count(); } }

    @Test public void groupsOutputFoldersCompactPackagesAndSqlDaoFoldersWithSafeDefaults() throws Exception {
        var java = entity("Employee",true); var query = sql("selectById",false); var model = model(java,query);
        assertEquals(List.of("resources","src"),model.roots().stream().map(n -> n.label).toList());
        Node sourceRoot = model.roots().stream().filter(n -> n.label.equals("src")).findFirst().orElseThrow();
        assertEquals("example.entity",sourceRoot.children().get(0).label);
        assertEquals("Employee.java",sourceRoot.children().get(0).children().get(0).label);
        Node resourceRoot = model.roots().stream().filter(n -> n.label.equals("resources")).findFirst().orElseThrow();
        assertEquals("META-INF",resourceRoot.children().get(0).label);
        assertEquals("example.dao",resourceRoot.children().get(0).children().get(0).label);
        assertEquals("EmployeeDao",resourceRoot.children().get(0).children().get(0).children().get(0).label);
        assertEquals("Merge",leaf(model,java).entry.operation()); assertEquals("Create",leaf(model,query).entry.operation());
        assertEquals(2,model.checkedCount()); assertFalse(model.canApply()); assertEquals(0,baselineCount());
    }
    @Test public void parentChecksPropagateAndPartialSelectionIsIndeterminate() throws Exception {
        var one=entity("One",false); var two=entity("Two",false); var query=sql("find",false); var model=model(one,two,query);
        Node pkg=leaf(model,one).parent, root=pkg.parent;
        model.setChecked(leaf(model,one),false);
        assertTrue(model.isChecked(pkg)); assertTrue(model.isGrayed(pkg)); assertTrue(model.isGrayed(root));
        model.setChecked(root,false); assertFalse(model.isChecked(root)); assertFalse(model.isGrayed(root)); assertEquals(1,model.checkedCount());
        model.setChecked(pkg,true); assertEquals(3,model.checkedCount()); assertFalse(model.isGrayed(root));
        model.setAllChecked(false); assertEquals(0,model.checkedCount());
    }
    @Test public void batchActionsAffectOnlyCheckedExistingFilesAndNewFilesRemainCreate() throws Exception {
        var one=entity("One",true); var two=entity("Two",true); var fresh=entity("Fresh",false); var model=model(one,two,fresh);
        model.setChecked(leaf(model,one),false); model.applyToChecked(Action.OVERWRITE);
        assertEquals(Action.MERGE,leaf(model,one).entry.action()); assertEquals("Skip",leaf(model,one).entry.operation());
        assertEquals(Action.OVERWRITE,leaf(model,two).entry.action()); assertEquals("Create",leaf(model,fresh).entry.operation());
        assertEquals(Action.MERGE,leaf(model,fresh).entry.action());
        assertEquals(2,model.selectedPlans().size()); assertFalse(read(two.file()).contains("private Long id"));
        assertThrows(GenerationException.class,() -> model.applyToChecked(Action.KEEP));
    }
    @Test public void uncheckedFilesAreAbsentFromComparisonWritesAndBaselines() throws Exception {
        var java=entity("Employee",false); var query=sql("find",true); var model=model(java,query); String original=read(query.file());
        model.setChecked(leaf(model,query),false); model.markCompared(model.revision()); assertTrue(model.canApply());
        var comparison=new TableCompareInput(model.selectedPlans()); comparison.run(new NullProgressMonitor());
        IDiffElement[] differences=((IDiffContainer)comparison.getCompareResult()).getChildren(); assertEquals(1,differences.length);
        assertTrue(differences[0].getName().endsWith("Employee.java"));
        updater.apply(model.selectedPlans(),null);
        assertTrue(java.file().exists()); assertEquals(original,read(query.file())); assertEquals(1,baselineCount());
    }
    @Test public void uncheckedStaleFilesDoNotBlockTheCheckedBatch() throws Exception {
        var one=entity("One",true); var two=entity("Two",true); var model=model(one,two);
        model.setChecked(leaf(model,one),false); model.markCompared(model.revision()); write(one.file(),"// external\nclass One {}\n");
        updater.apply(model.selectedPlans(),null);
        assertEquals("// external\nclass One {}\n",read(one.file())); assertTrue(read(two.file()).contains("private Long id")); assertEquals(1,baselineCount());
    }
    @Test public void changingChecksOrActionsRequiresANewComparisonButNoOpChangesDoNot() throws Exception {
        var one=entity("One",true); var two=entity("Two",false); var model=model(one,two);
        long old=model.revision(); model.markCompared(old); assertTrue(model.canApply());
        model.setAllChecked(true); assertTrue(model.canApply());
        model.setChecked(leaf(model,two),false); assertFalse(model.canApply()); model.markCompared(old); assertFalse(model.canApply());
        model.markCompared(model.revision()); assertTrue(model.canApply());
        model.applyToChecked(Action.OVERWRITE); assertFalse(model.canApply()); model.markCompared(model.revision()); assertTrue(model.canApply());
        model.applyToChecked(Action.OVERWRITE); assertTrue(model.canApply());
        model.setChecked(leaf(model,two),true); assertFalse(model.canApply());
    }
    @Test public void uncheckedConflictsDoNotBlockValidPlansAndOverwriteCanResolveTheConflict() throws Exception {
        var bad=entity("Conflicted",true); write(bad.file(),"package example.entity;\npublic class Conflicted { private String id; }\n");
        var fresh=entity("Fresh",false); var model=model(bad,fresh);
        assertEquals(1,model.checkedErrors()); assertFalse(model.hasChanges()); assertNotNull(leaf(model,bad).entry.error());
        model.setChecked(leaf(model,bad),false); assertTrue(model.hasChanges()); assertEquals(1,model.selectedPlans().size());
        model.setChecked(leaf(model,bad),true); model.setAction(leaf(model,bad).entry,Action.OVERWRITE);
        assertEquals(0,model.checkedErrors()); assertTrue(model.hasChanges());
        model.markCompared(model.revision()); model.setAction(leaf(model,bad).entry,Action.MERGE);
        assertFalse(model.canApply()); assertEquals(1,model.checkedErrors());
    }
    @Test public void dirtyExistingEditorsCanBeUncheckedWhileOtherFilesAreGenerated() throws Exception {
        var old=entity("Dirty",true); var fresh=entity("Fresh",false); String saved=read(old.file());
        var manager=FileBuffers.getTextFileBufferManager(); manager.connect(old.file().getFullPath(),LocationKind.IFILE,null);
        try {
            var buffer=manager.getTextFileBuffer(old.file().getFullPath(),LocationKind.IFILE); buffer.getDocument().set("// unsaved user changes");
            var model=model(old,fresh); assertEquals(1,model.checkedErrors()); model.setChecked(leaf(model,old),false); model.markCompared(model.revision());
            assertTrue(model.canApply()); updater.apply(model.selectedPlans(),null);
            assertEquals(saved,read(old.file())); assertEquals("// unsaved user changes",buffer.getDocument().get()); assertTrue(fresh.file().exists());
        } finally { manager.disconnect(old.file().getFullPath(),LocationKind.IFILE,null); }
    }
    @Test public void allUncheckedOrOnlyUnchangedFilesCannotBeGenerated() throws Exception {
        var query=sql("find",true); var model=model(query); assertFalse(model.hasChanges()); model.markCompared(model.revision()); assertFalse(model.canApply());
        model.setAllChecked(false); assertFalse(model.validSelection()); assertThrows(GenerationException.class,model::selectedPlans);
        assertEquals(0,baselineCount());
    }
    @Test public void checkedFilesStillRejectStaleContentAfterComparisonAndActionChanges() throws Exception {
        var old=entity("Employee",true); var model=model(old);
        write(old.file(),"// latest user changes\nclass Employee {}\n"); model.applyToChecked(Action.OVERWRITE); model.markCompared(model.revision());
        assertThrows(GenerationException.class,() -> updater.apply(model.selectedPlans(),null));
        assertEquals("// latest user changes\nclass Employee {}\n",read(old.file())); assertEquals(0,baselineCount());
    }
    @Test public void newFilesCreatedAfterPreviewAreProtectedWhenChecksAreChanged() throws Exception {
        var fresh=entity("Employee",false); var model=model(fresh); create(fresh.file(),"// handwritten\nclass Employee {}\n");
        model.setAllChecked(false); model.setAllChecked(true); model.markCompared(model.revision());
        assertThrows(GenerationException.class,() -> updater.apply(model.selectedPlans(),null)); assertTrue(read(fresh.file()).contains("handwritten"));
    }
    @Test public void cancelingPreviewPreparationAndDuplicateTargetsNeverWriteFiles() throws Exception {
        var one=entity("One",false); var two=entity("Two",false); AtomicInteger ticks=new AtomicInteger();
        assertThrows(CancellationException.class,() -> new ProposedFilesModel(updater,List.of(one,two),() -> { if(ticks.incrementAndGet()==2)throw new CancellationException(); }));
        assertFalse(one.file().exists()); assertFalse(two.file().exists()); assertEquals(0,baselineCount());
        assertThrows(GenerationException.class,() -> model(one,one));
    }
}
