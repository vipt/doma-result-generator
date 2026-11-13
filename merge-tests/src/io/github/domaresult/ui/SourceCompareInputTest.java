/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import static org.junit.Assert.*;
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
}
