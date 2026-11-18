/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.eclipse;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.launching.JavaRuntime;

/** Project runtime classpath, including compiled outputs and referenced projects. */
public final class ExpressionClasspath {
    private ExpressionClasspath() {}
    public static URLClassLoader open(IJavaProject project, boolean includeProject, String additional) throws CoreException {
        try {
            LinkedHashSet<URL> urls = new LinkedHashSet<>();
            if (includeProject) for (String path : JavaRuntime.computeDefaultRuntimeClassPath(project)) urls.add(Path.of(path).toUri().toURL());
            for (String path : additional.split(";")) if (!path.isBlank()) urls.add(Path.of(path.strip()).toUri().toURL());
            return new URLClassLoader(urls.toArray(URL[]::new), ExpressionClasspath.class.getClassLoader());
        } catch (java.net.MalformedURLException | java.nio.file.InvalidPathException e) {
            throw new io.github.domaresult.core.GenerationException("Check the expression classpath JAR/folder paths.");
        }
    }
}
