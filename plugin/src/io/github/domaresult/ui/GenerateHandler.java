/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.handlers.HandlerUtil;

public final class GenerateHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        var shell = HandlerUtil.getActiveShell(event);
        var part = HandlerUtil.getActivePart(event);
        IEditorPart editor = part instanceof IEditorPart active ? active : null;
        IFile file = targetFile(HandlerUtil.getCurrentSelection(event), editor == null ? null : editor.getEditorInput());
        if (file == null) return null;
        try {
            IJavaProject project = JavaCore.create(file.getProject());
            List<IPackageFragmentRoot> roots = new ArrayList<>();
            for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE && !root.isReadOnly()
                        && root.getJavaProject().equals(project)) roots.add(root);
            }
            if (roots.isEmpty()) {
                MessageDialog.openError(shell, "Doma Result Generator", "The SQL must be in a Java project with a writable source folder.");
                return null;
            }
            if (editor != null && editor.isDirty() && FileBuffers.getTextFileBufferManager()
                    .getTextFileBuffer(file.getFullPath(), LocationKind.IFILE) == null) {
                MessageDialog.openInformation(shell, "Doma Result Generator", "Save the SQL file before opening the generator from this editor.");
                return null;
            }
            String sql = readSql(file, editor != null);
            new GenerateDialog(shell, HandlerUtil.getActiveWorkbenchWindow(event), file, roots, sql).open();
        } catch (Exception e) {
            MessageDialog.openError(shell, "Doma Result Generator", "Cannot open generator: " + e.getClass().getSimpleName());
        }
        return null;
    }

    static IFile targetFile(ISelection selection, IEditorInput editorInput) {
        IFile file;
        if (editorInput != null) file = editorInput instanceof IFileEditorInput input ? input.getFile() : Adapters.adapt(editorInput, IFile.class);
        else if (selection instanceof IStructuredSelection structured && structured.size() == 1) file = Adapters.adapt(structured.getFirstElement(), IFile.class);
        else return null;
        return file != null && "sql".equalsIgnoreCase(file.getFileExtension()) ? file : null;
    }

    static String readSql(IFile file, boolean fromEditor) throws Exception {
        if (fromEditor) {
            var buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
            if (buffer != null) return buffer.getDocument().get();
        }
        try (var stream = file.getContents()) {
            return new String(stream.readAllBytes(), Charset.forName(file.getCharset()));
        }
    }
}
