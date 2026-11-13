/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.navigation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IAnnotatable;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;

/** Resolves the selected Java symbol, then searches the DAO's source classpath. */
public final class DaoSqlNavigator {
    private DaoSqlNavigator() {}

    public static List<IFile> findFiles(ICompilationUnit unit, int offset, int length) throws CoreException {
        Set<IFile> files = new LinkedHashSet<>();
        // codeSelect uses the editor's working copy, including unsaved changes. Do not
        // use getElementAt: in a service that would return the enclosing service method.
        for (var element : unit.codeSelect(offset, length)) {
            if (!(element instanceof IMethod method) || method.isConstructor()) continue;
            IType dao = method.getDeclaringType();
            if (dao == null || !dao.isInterface() || !hasAnnotation(dao, dao, "Dao")) continue;
            // Inline @Sql has no external SQL file, even if a stale file exists.
            if (hasAnnotation(method, dao, "Sql")) continue;
            String extension = hasAnnotation(method, dao, "Script") ? "script" : "sql";
            String directory = "META-INF/" + dao.getFullyQualifiedName('.').replace('.', '/');
            for (var root : dao.getJavaProject().getAllPackageFragmentRoots()) {
                if (root.getKind() != IPackageFragmentRoot.K_SOURCE
                        || !(root.getResource() instanceof IContainer container)) continue;
                var folder = container.getFolder(new Path(directory));
                if (!folder.exists()) continue;
                for (IResource resource : folder.members()) {
                    if (resource instanceof IFile file
                            && matches(file.getName(), method.getElementName(), extension)) files.add(file);
                }
            }
        }
        List<IFile> result = new ArrayList<>(files);
        result.sort(Comparator.comparing(file -> file.getFullPath().toPortableString()));
        return List.copyOf(result);
    }

    private static boolean matches(String file, String method, String extension) {
        String suffix = "." + extension;
        return file.equals(method + suffix) || (file.startsWith(method + "-")
                && file.endsWith(suffix) && file.length() > method.length() + 1 + suffix.length());
    }

    private static boolean hasAnnotation(IAnnotatable element, IType context, String name)
            throws JavaModelException {
        String qualified = "org.seasar.doma." + name;
        for (var annotation : element.getAnnotations()) {
            String actual = annotation.getElementName();
            if (qualified.equals(actual)) return true;
            String[][] resolved = context.resolveType(actual);
            if (resolved != null) {
                for (String[] type : resolved) {
                    if (qualified.equals(type[0] + "." + type[1])) return true;
                }
            }
        }
        return false;
    }
}
