/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import java.util.ArrayList;
import org.eclipse.core.commands.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jdt.core.*;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.*;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.handlers.HandlerUtil;

public final class TableGenerationHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        var window = HandlerUtil.getActiveWorkbenchWindow(event);
        IProject project = project(HandlerUtil.getCurrentSelection(event));
        if (project == null && window.getActivePage() != null && window.getActivePage().getActiveEditor() != null
                && window.getActivePage().getActiveEditor().getEditorInput() instanceof IFileEditorInput input) project = input.getFile().getProject();
        try {
            if (project == null || !project.isAccessible() || !project.hasNature(JavaCore.NATURE_ID)) {
                MessageDialog.openInformation(window.getShell(), "Doma table generator", "Select a Java project or a file/source folder in that project."); return null;
            }
            IJavaProject java = JavaCore.create(project);
            var roots = new ArrayList<IPackageFragmentRoot>();
            for (var root : java.getPackageFragmentRoots()) if (root.getKind() == IPackageFragmentRoot.K_SOURCE
                    && !root.isReadOnly() && root.getJavaProject().equals(java) && root.getResource() instanceof IContainer) roots.add(root);
            if (roots.isEmpty()) {
                MessageDialog.openError(window.getShell(), "Doma table generator", "The project needs a writable Java source folder."); return null;
            }
            new TableGenerationDialog(window.getShell(), project, roots).open();
        } catch (Exception e) { MessageDialog.openError(window.getShell(), "Doma table generator", "Cannot open generator: " + e.getClass().getSimpleName()); }
        return null;
    }
    static IProject project(ISelection selection) {
        if (!(selection instanceof IStructuredSelection structured) || structured.size() != 1) return null;
        Object item = structured.getFirstElement();
        if (item instanceof IJavaElement java) return java.getJavaProject() == null ? null : java.getJavaProject().getProject();
        IResource resource = Adapters.adapt(item, IResource.class);
        return resource == null ? null : resource.getProject();
    }
}
