/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import org.eclipse.core.expressions.PropertyTester;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.JavaCore;

public final class JavaProjectPropertyTester extends PropertyTester {
    @Override public boolean test(Object receiver, String property, Object[] args, Object expectedValue) {
        if (!(receiver instanceof IResource resource) || !"javaProject".equals(property)) return false;
        try { return resource.getProject() != null && resource.getProject().isAccessible() && resource.getProject().hasNature(JavaCore.NATURE_ID); }
        catch (CoreException e) { return false; }
    }
}
