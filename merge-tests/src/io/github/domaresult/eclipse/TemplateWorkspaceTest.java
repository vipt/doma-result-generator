/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.eclipse;

import static org.junit.Assert.*;
import io.github.domaresult.core.DomaGenTemplates;
import io.github.domaresult.core.GenerationException;
import io.github.domaresult.core.TemplateFiles;
import java.nio.file.*;
import java.util.*;
import org.eclipse.core.filebuffers.*;
import org.eclipse.core.resources.*;
import org.junit.*;

public class TemplateWorkspaceTest {
    private IProject project;
    private Path directory;
    @Before public void setup() throws Exception {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("ftl-editor-" + UUID.randomUUID());
        project.create(null); project.open(null);
        directory = project.getLocation().append("doma-gen-templates").toFile().toPath();
    }
    @After public void cleanup() throws Exception { if (project != null) project.delete(true, true, null); }
    @Test public void refreshMakesCopiedTemplatesVisibleInExplorer() throws Exception {
        var files = new TemplateFiles(directory); files.copyMissingDefaults();
        TemplateWorkspace.refreshDirectory(directory);
        IFolder folder = project.getFolder("doma-gen-templates"); assertTrue(folder.exists()); assertEquals(6, folder.members().length);
        for (String name : DomaGenTemplates.NAMES) assertTrue(folder.getFile(name).isSynchronized(IResource.DEPTH_ZERO));
    }
    @Test public void doesNotOverwriteAnUnsavedWorkspaceTemplateBuffer() throws Exception {
        var files = new TemplateFiles(directory); files.copyMissingDefaults(); TemplateWorkspace.refreshDirectory(directory);
        IFile file = project.getFile("doma-gen-templates/dao.ftl");
        var manager = FileBuffers.getTextFileBufferManager(); manager.connect(file.getFullPath(), LocationKind.IFILE, null);
        String saved = files.read("dao.ftl").source();
        try {
            manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE).getDocument().set("// unsaved FTL edit\n");
            assertThrows(GenerationException.class, () -> TemplateWorkspace.ensureSaved(files.file("dao.ftl")));
            assertEquals(saved, files.read("dao.ftl").source());
            assertEquals("// unsaved FTL edit\n", manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE).getDocument().get());
        } finally { manager.disconnect(file.getFullPath(), LocationKind.IFILE, null); }
    }
    @Test public void detectsUnsavedExternalTextEditorAndAllowsSavedFiles() throws Exception {
        Path external = Files.createTempFile("external-template-", ".ftl");
        var path = org.eclipse.core.runtime.Path.fromOSString(external.toString());
        var manager = FileBuffers.getTextFileBufferManager(); manager.connect(path, LocationKind.LOCATION, null);
        try {
            TemplateWorkspace.ensureSaved(external);
            manager.getTextFileBuffer(path, LocationKind.LOCATION).getDocument().set("// external unsaved edit");
            assertThrows(GenerationException.class, () -> TemplateWorkspace.ensureSaved(external));
            assertEquals("", Files.readString(external));
        } finally { manager.disconnect(path, LocationKind.LOCATION, null); Files.deleteIfExists(external); }
    }
    @Test public void bundledEffectiveDefaultsAndFreeMarkerSyntaxCheckWorkInsideEclipse() throws Exception {
        for (String name : DomaGenTemplates.NAMES) DomaGenTemplates.validate(name, DomaGenTemplates.source(name));
        var files = new TemplateFiles(directory); files.copyMissingDefaults(); TemplateWorkspace.refreshDirectory(directory);
        var original = files.read("entity.ftl");
        TemplateWorkspace.ensureSaved(files.file("entity.ftl"));
        files.save(original, "// Changed in FTL editor\n" + original.source()); TemplateWorkspace.refreshDirectory(directory);
        assertTrue(project.getFile("doma-gen-templates/entity.ftl").isSynchronized(IResource.DEPTH_ZERO));
        assertTrue(files.read("entity.ftl").source().startsWith("// Changed in FTL editor"));
    }
}
