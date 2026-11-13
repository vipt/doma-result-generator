/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
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
import org.eclipse.ui.handlers.HandlerUtil;

public final class GenerateHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        var shell = HandlerUtil.getActiveShell(event);
        var selection = HandlerUtil.getCurrentSelection(event);
        if (!(selection instanceof IStructuredSelection structured) || structured.size() != 1) return null;
        IFile file = Adapters.adapt(structured.getFirstElement(), IFile.class);
        if (file == null || !"sql".equalsIgnoreCase(file.getFileExtension())) return null;
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
            String sql;
            try (var stream = file.getContents()) {
                sql = new String(stream.readAllBytes(), Charset.forName(file.getCharset()));
            }
            new GenerateDialog(shell, HandlerUtil.getActiveWorkbenchWindow(event), file, roots, sql).open();
        } catch (Exception e) {
            MessageDialog.openError(shell, "Doma Result Generator", "Cannot open generator: " + e.getClass().getSimpleName());
        }
        return null;
    }
}
