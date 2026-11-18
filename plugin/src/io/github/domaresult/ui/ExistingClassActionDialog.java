/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import io.github.domaresult.core.ExistingClassAction;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;

/** The choice prepares a preview; it never writes or changes the existing file. */
final class ExistingClassActionDialog {
    private ExistingClassActionDialog() {}

    static ExistingClassAction open(Shell parent, String path) {
        var dialog = new MessageDialog(parent, "Existing result class", null,
                "A Java file already exists:\n" + path + "\n\n"
                + "Merge: keep compatible existing fields, custom methods and comments.\n"
                + "Overwrite: replace the entire file with the generated class. Existing custom methods and comments will be removed.\n\n"
                + "No file is changed until you review the comparison and select Merge or Overwrite in the generator dialog.",
                MessageDialog.QUESTION, new String[] { "Merge", "Overwrite", "Cancel" }, 0);
        return fromReturnCode(dialog.open());
    }

    static ExistingClassAction fromReturnCode(int code) {
        return switch (code) {
            case 0 -> ExistingClassAction.MERGE;
            case 1 -> ExistingClassAction.OVERWRITE;
            default -> null;
        };
    }
}
