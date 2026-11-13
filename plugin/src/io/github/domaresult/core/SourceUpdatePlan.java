/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

/** A preview may only be applied to the exact saved version it was built from. */
public record SourceUpdatePlan(String before, String after, long modificationStamp) {
    public boolean hasChanges() { return !before.equals(after); }

    public void verifyCurrent(String currentSource, long currentStamp, boolean dirty) {
        if (dirty) throw new GenerationException("Save the existing class and refresh Preview before updating; it has unsaved edits.");
        if (currentStamp != modificationStamp || !before.equals(currentSource)) {
            throw new GenerationException("The class changed after Preview. Refresh Preview to review the latest changes.");
        }
    }
}
