/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.expressions.EvaluationResult;
import org.eclipse.core.expressions.Expression;
import org.eclipse.core.expressions.ExpressionConverter;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.part.FileEditorInput;
import org.junit.*;

/** Checks the shipped menu expression and real saved/unsaved Eclipse SQL file buffers. */
public class GenerateHandlerTest {
    private IProject project;
    private IFile sql, java;

    @Before public void setUp() throws Exception {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("generate-" + UUID.randomUUID());
        project.create(null);
        project.open(null);
        project.setDefaultCharset("UTF-8", null);
        sql = file("select.sql", "select 1 as saved_value");
        java = file("Result.java", "class Result {}");
    }

    @After public void tearDown() throws Exception {
        if (project != null && project.exists()) project.delete(true, true, null);
    }

    @Test public void resolvesExplorerSelectionAndRejectsOtherFilesOrMultipleSelection() {
        assertEquals(sql, GenerateHandler.targetFile(new StructuredSelection(sql), null));
        assertNull(GenerateHandler.targetFile(new StructuredSelection(java), null));
        assertNull(GenerateHandler.targetFile(new StructuredSelection(new Object[] { sql, java }), null));
        assertNull(GenerateHandler.targetFile(StructuredSelection.EMPTY, null));
    }

    @Test public void resolvesSqlEditorInputAndDoesNotFallBackToAnUnrelatedExplorerSelection() {
        assertEquals(sql, GenerateHandler.targetFile(new TextSelection(3, 5), new FileEditorInput(sql)));
        assertEquals(sql, GenerateHandler.targetFile(new StructuredSelection(java), new FileEditorInput(sql)));
        assertNull(GenerateHandler.targetFile(new StructuredSelection(sql), new FileEditorInput(java)));
    }

    @Test public void editorReadsUnsavedUtf8SqlWithoutSavingOrChangingTheFile() throws Exception {
        var manager = FileBuffers.getTextFileBufferManager();
        manager.connect(sql.getFullPath(), LocationKind.IFILE, null);
        try {
            var buffer = manager.getTextFileBuffer(sql.getFullPath(), LocationKind.IFILE);
            String current = "select '日本語' as current_value";
            buffer.getDocument().set(current);
            assertTrue(buffer.isDirty());
            assertEquals(current, GenerateHandler.readSql(sql, true));
            assertEquals("select 1 as saved_value", GenerateHandler.readSql(sql, false));
            assertTrue(buffer.isDirty());
        } finally { manager.disconnect(sql.getFullPath(), LocationKind.IFILE, null); }
    }

    @Test public void editorWithNoOpenBufferReadsTheSavedSql() throws Exception {
        assertEquals("select 1 as saved_value", GenerateHandler.readSql(sql, true));
    }

    @Test public void shippedMenuIsVisibleInSqlExplorerAndSqlTextEditorOnly() throws Exception {
        Expression expression = menuExpression();
        Object view = part(IViewPart.class);
        Object editor = part(IEditorPart.class);
        assertEquals(EvaluationResult.TRUE, evaluate(expression, new StructuredSelection(sql), view, java));
        assertEquals(EvaluationResult.TRUE, evaluate(expression, new TextSelection(0, 0), editor, sql));
        assertEquals(EvaluationResult.FALSE, evaluate(expression, new TextSelection(0, 0), editor, java));
        assertEquals(EvaluationResult.FALSE, evaluate(expression, new TextSelection(0, 0), view, sql));
        assertEquals(EvaluationResult.FALSE, evaluate(expression, new StructuredSelection(java), view, sql));
        assertEquals(EvaluationResult.FALSE, evaluate(expression, new StructuredSelection(new Object[] { sql, java }), view, sql));
    }

    private Expression menuExpression() throws Exception {
        for (var element : Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.menus")) {
            if (!element.getContributor().getName().equals("io.github.domaresult.eclipse")) continue;
            for (var command : element.getChildren("command")) {
                if ("io.github.domaresult.generate".equals(command.getAttribute("commandId"))) {
                    return ExpressionConverter.getDefault().perform(command.getChildren("visibleWhen")[0].getChildren()[0]);
                }
            }
        }
        throw new AssertionError("Missing generator menu contribution");
    }

    private EvaluationResult evaluate(Expression expression, ISelection selection, Object part, IFile editorFile) throws Exception {
        var context = new EvaluationContext(null, selection);
        context.setAllowPluginActivation(true);
        context.addVariable("activeMenuSelection", selection);
        context.addVariable("activePart", part);
        context.addVariable("activeEditorInput", new FileEditorInput(editorFile));
        return expression.evaluate(context);
    }

    private Object part(Class<?> type) {
        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> null);
    }

    private IFile file(String name, String text) throws Exception {
        IFile file = project.getFile(name);
        file.create(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), true, null);
        return file;
    }
}
