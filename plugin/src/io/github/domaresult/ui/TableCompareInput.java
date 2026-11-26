/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import io.github.domaresult.eclipse.TableWorkspaceUpdater.Plan;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.eclipse.compare.*;
import org.eclipse.compare.structuremergeviewer.*;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.graphics.Image;

/** Read-only comparison tree for changed files in the supplied (checked) generation plans. */
final class TableCompareInput extends CompareEditorInput {
    private final List<Plan> plans;
    TableCompareInput(List<Plan> plans) {
        super(configuration());
        this.plans = List.copyOf(plans);
        setTitle("Review generated DAO, Entity and SQL files");
    }
    private static CompareConfiguration configuration() {
        var config = new CompareConfiguration();
        config.setLeftEditable(false); config.setRightEditable(false);
        config.setLeftLabel("Current saved file"); config.setRightLabel("Proposed file (selected action)");
        return config;
    }
    @Override protected Object prepareInput(IProgressMonitor monitor) {
        DiffNode root = new DiffNode(Differencer.CHANGE) {
            @Override public String getName() { return "Generated files"; }
        };
        for (Plan plan : plans) {
            if (!plan.changed()) continue;
            String name = plan.snapshot().file().getProjectRelativePath().toPortableString();
            var left = plan.snapshot().source() == null ? null : new Source(name, plan.snapshot().source());
            new DiffNode(root, left == null ? Differencer.ADDITION : Differencer.CHANGE, null, left, new Source(name, plan.after()));
        }
        return root;
    }
    private record Source(String name, String source) implements ITypedElement, IEncodedStreamContentAccessor {
        @Override public String getName() { return name; }
        @Override public String getType() { return name.endsWith(".java") ? "java" : "sql"; }
        @Override public Image getImage() { return null; }
        @Override public String getCharset() { return StandardCharsets.UTF_8.name(); }
        @Override public InputStream getContents() { return new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)); }
    }
}
