/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import io.github.domaresult.navigation.DaoSqlNavigator;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.ide.IDE;

public final class JumpToSqlHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        var editor = HandlerUtil.getActiveEditor(event);
        if (editor == null) return null;
        ICompilationUnit unit = JavaUI.getWorkingCopyManager().getWorkingCopy(editor.getEditorInput());
        if (unit == null && JavaUI.getEditorInputJavaElement(editor.getEditorInput()) instanceof ICompilationUnit saved) {
            unit = saved;
        }
        var provider = editor.getSite().getSelectionProvider();
        if (unit == null || provider == null || !(provider.getSelection() instanceof ITextSelection text)) return null;
        var shell = HandlerUtil.getActiveShell(event);
        try {
            var files = DaoSqlNavigator.findFiles(unit, text.getOffset(), text.getLength());
            if (files.isEmpty()) {
                MessageDialog.openInformation(shell, "Jump to Doma SQL",
                        "No matching SQL file was found. Place the cursor on a method name declared in an @Dao interface. "
                        + "SQL files must be in META-INF/<DAO package>/<DAO name>/ under a source folder on the DAO's build path. "
                        + "Inline SQL and methods without external SQL files cannot be opened.");
                return null;
            }
            IFile file = files.get(0);
            if (files.size() > 1) {
                var dialog = new ElementListSelectionDialog(shell, new LabelProvider() {
                    @Override public String getText(Object item) {
                        return ((IFile) item).getFullPath().toPortableString();
                    }
                });
                dialog.setTitle("Jump to Doma SQL");
                dialog.setMessage("Select a SQL file (including dialect-specific variants):");
                dialog.setElements(files.toArray());
                dialog.setMultipleSelection(false);
                if (dialog.open() != Window.OK) return null;
                file = (IFile) dialog.getFirstResult();
                if (file == null) return null;
            }
            IDE.openEditor(editor.getSite().getPage(), file, true);
        } catch (CoreException e) {
            MessageDialog.openError(shell, "Jump to Doma SQL", "Cannot open SQL file: " + e.getMessage());
        }
        return null;
    }
}
