/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import io.github.domaresult.core.ExistingClassAction;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.graphics.Image;

/** Read-only comparison; only the generator dialog's final action writes a file. */
final class SourceCompareInput extends CompareEditorInput {
    private final String name, before, after;

    SourceCompareInput(String name, String before, String after) {
        this(name, before, after, ExistingClassAction.MERGE);
    }

    SourceCompareInput(String name, String before, String after, ExistingClassAction action) {
        super(configuration(action));
        this.name = name;
        this.before = before;
        this.after = after;
        setTitle((action == ExistingClassAction.OVERWRITE ? "Review overwrite: " : "Review merge: ") + name);
    }

    private static CompareConfiguration configuration(ExistingClassAction action) {
        CompareConfiguration config = new CompareConfiguration();
        config.setLeftEditable(false);
        config.setRightEditable(false);
        config.setLeftLabel("Current saved class");
        config.setRightLabel(action == ExistingClassAction.OVERWRITE ? "Proposed overwrite" : "Proposed merge");
        return config;
    }

    @Override
    protected Object prepareInput(IProgressMonitor monitor) {
        return new DiffNode(new Source(name, before), new Source(name, after));
    }

    private record Source(String name, String source) implements ITypedElement, IEncodedStreamContentAccessor {
        @Override public String getName() { return name; }
        @Override public String getType() { return "java"; }
        @Override public Image getImage() { return null; }
        @Override public String getCharset() { return StandardCharsets.UTF_8.name(); }
        @Override public InputStream getContents() { return new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)); }
    }
}
