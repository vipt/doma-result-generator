/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import static org.junit.Assert.*;
import io.github.domaresult.core.TableGenerator.*;
import io.github.domaresult.eclipse.TableWorkspaceUpdater.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.structuremergeviewer.*;
import org.eclipse.core.expressions.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.viewers.StructuredSelection;
import org.junit.*;

public class TableGenerationUiTest {
    private IProject project;
    @Before public void setup() throws Exception {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("table-menu-" + UUID.randomUUID()); project.create(null); project.open(null);
        var description = project.getDescription(); description.setNatureIds(new String[]{JavaCore.NATURE_ID}); project.setDescription(description, null);
    }
    @After public void cleanup() throws Exception { if (project != null) project.delete(true, true, null); }
    @Test public void resolvesResourceAndJavaElementSelectionsWithinTheProject() {
        assertEquals(project, TableGenerationHandler.project(new StructuredSelection(project)));
        assertEquals(project, TableGenerationHandler.project(new StructuredSelection(JavaCore.create(project))));
        assertEquals(project, TableGenerationHandler.project(new StructuredSelection(project.getFile("any.sql"))));
        assertNull(TableGenerationHandler.project(StructuredSelection.EMPTY));
        assertNull(TableGenerationHandler.project(new StructuredSelection(new Object[]{project, project.getFile("any.sql")})));
    }
    @Test public void shippedPopupIsVisibleOnlyForJavaProjects() throws Exception {
        Expression expression = null;
        for (var extension : Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.menus")) {
            if (!"popup:org.eclipse.ui.popup.any?after=additions".equals(extension.getAttribute("locationURI"))) continue;
            for (var command : extension.getChildren("command")) {
                if ("io.github.domaresult.generateTables".equals(command.getAttribute("commandId"))) expression = ExpressionConverter.getDefault().perform(command.getChildren("visibleWhen")[0].getChildren()[0]);
            }
        }
        assertNotNull(expression);
        var selection = new StructuredSelection(project);
        var context = new EvaluationContext(null, selection); context.setAllowPluginActivation(true); context.addVariable("activeMenuSelection", selection);
        assertEquals(EvaluationResult.TRUE, expression.evaluate(context));
        var description = project.getDescription(); description.setNatureIds(new String[0]); project.setDescription(description, null);
        assertEquals(EvaluationResult.FALSE, expression.evaluate(context));
    }
    @Test public void comparisonContainsAllChangedFilesAndCannotWriteThem() throws Exception {
        IFile old = project.getFile("Employee.java"); String before = "// handwritten\nclass Employee {}\n";
        old.create(new ByteArrayInputStream(before.getBytes(StandardCharsets.UTF_8)), false, null);
        IFile sql = project.getFile("selectById.sql");
        var entity = new Plan(new Artifact(Kind.ENTITY,"Employee.java","class Employee { private Long id; }\n"),
                new Snapshot(old, before, old.getModificationStamp(), "UTF-8"), Action.OVERWRITE, "class Employee { private Long id; }\n", "Overwrite");
        var query = new Plan(new Artifact(Kind.SQL,"selectById.sql","select id from employee\n"),
                new Snapshot(sql, null, IResource.NULL_STAMP, "UTF-8"), Action.MERGE, "select id from employee\n", "Create");
        var input = new TableCompareInput(List.of(entity, query)); input.run(new NullProgressMonitor());
        var root = (IDiffContainer) input.getCompareResult(); assertEquals(2, root.getChildren().length);
        var java = (ICompareInput) root.getChildren()[0]; var sqlDiff = (ICompareInput) root.getChildren()[1];
        assertEquals("java", java.getRight().getType()); assertEquals("sql", sqlDiff.getRight().getType());
        try (var stream = ((IEncodedStreamContentAccessor) java.getLeft()).getContents()) { assertEquals(before, new String(stream.readAllBytes(), StandardCharsets.UTF_8)); }
        assertNull(sqlDiff.getLeft()); assertFalse(input.getCompareConfiguration().isLeftEditable());
        assertFalse(input.getCompareConfiguration().isRightEditable()); assertFalse(input.isSaveNeeded());
        assertFalse(sql.exists()); try (var stream = old.getContents()) { assertEquals(before, new String(stream.readAllBytes(), StandardCharsets.UTF_8)); }
    }
}
