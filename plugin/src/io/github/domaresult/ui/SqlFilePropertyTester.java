/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import org.eclipse.core.expressions.PropertyTester;
import org.eclipse.core.resources.IFile;

public final class SqlFilePropertyTester extends PropertyTester {
    static boolean isSqlFile(IFile file) { return file != null && "sql".equalsIgnoreCase(file.getFileExtension()); }

    @Override public boolean test(Object receiver, String property, Object[] args, Object expectedValue) {
        if (!property.equals("sqlFile")) return false;
        boolean result = receiver instanceof IFile file && isSqlFile(file);
        return expectedValue == null ? result : result == Boolean.parseBoolean(expectedValue.toString());
    }
}
