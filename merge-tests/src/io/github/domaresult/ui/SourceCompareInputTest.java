/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import static org.junit.Assert.*;
import io.github.domaresult.core.ExistingClassAction;
import java.nio.charset.StandardCharsets;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.structuremergeviewer.ICompareInput;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Test;

public class SourceCompareInputTest {
    @Test public void comparisonIsReadOnlyAndHasNoPendingSave() throws Exception {
        var input = new SourceCompareInput("Result.java", "before", "after");
        input.run(new NullProgressMonitor());
        assertFalse(input.getCompareConfiguration().isLeftEditable());
        assertFalse(input.getCompareConfiguration().isRightEditable());
        assertFalse(input.isSaveNeeded());
        assertNotNull(input.getCompareResult());
    }

    @Test public void beforeAndAfterKeepUtf8CommentsAndJavaContentType() throws Exception {
        String before = "// 手動コメント\nclass Result {}";
        String after = "// 手動コメント\nclass Result { int id; }";
        var input = new SourceCompareInput("Result.java", before, after);
        input.run(new NullProgressMonitor());
        ICompareInput result = (ICompareInput) input.getCompareResult();
        assertEquals("java", result.getLeft().getType());
        assertEquals("Result.java", result.getRight().getName());
        var left = (IEncodedStreamContentAccessor) result.getLeft();
        var right = (IEncodedStreamContentAccessor) result.getRight();
        assertEquals("UTF-8", left.getCharset());
        try (var stream = left.getContents()) { assertEquals(before, new String(stream.readAllBytes(), StandardCharsets.UTF_8)); }
        try (var stream = right.getContents()) { assertEquals(after, new String(stream.readAllBytes(), StandardCharsets.UTF_8)); }
    }

    @Test public void overwriteComparisonShowsTheRemovedManualCodeAndCannotSaveItself() throws Exception {
        String before = "class Result { /** Custom comment. */ public String display(){return \"x\";} }";
        String after = "class Result { private String code; }";
        var input = new SourceCompareInput("Result.java", before, after, ExistingClassAction.OVERWRITE);
        input.run(new NullProgressMonitor());
        assertEquals("Proposed overwrite", input.getCompareConfiguration().getRightLabel(null));
        assertTrue(input.getTitle().contains("overwrite"));
        assertFalse(input.getCompareConfiguration().isLeftEditable());
        assertFalse(input.getCompareConfiguration().isRightEditable());
        assertFalse(input.isSaveNeeded());
        var diff = (ICompareInput) input.getCompareResult();
        try (var stream = ((IEncodedStreamContentAccessor) diff.getLeft()).getContents()) { assertEquals(before, new String(stream.readAllBytes(), StandardCharsets.UTF_8)); }
        try (var stream = ((IEncodedStreamContentAccessor) diff.getRight()).getContents()) { assertEquals(after, new String(stream.readAllBytes(), StandardCharsets.UTF_8)); }
    }

    @Test public void cancelAndWindowCloseDoNotChooseOverwrite() {
        assertNull(ExistingClassActionDialog.fromReturnCode(2));
        assertNull(ExistingClassActionDialog.fromReturnCode(org.eclipse.swt.SWT.DEFAULT));
        assertEquals(ExistingClassAction.MERGE, ExistingClassActionDialog.fromReturnCode(0));
        assertEquals(ExistingClassAction.OVERWRITE, ExistingClassActionDialog.fromReturnCode(1));
    }
}
