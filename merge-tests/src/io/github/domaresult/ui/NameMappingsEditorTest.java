/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import static org.junit.Assert.*;
import io.github.domaresult.core.GenerationException;
import io.github.domaresult.core.NameMappingsFile;
import io.github.domaresult.eclipse.TemplateWorkspace;
import java.nio.file.*;
import java.util.*;
import org.eclipse.core.filebuffers.*;
import org.eclipse.core.resources.*;
import org.junit.*;

public class NameMappingsEditorTest {
    private IProject project;
    private Path file;
    @Before public void setup() throws Exception {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("name-properties-" + UUID.randomUUID());
        project.create(null); project.open(null); file = project.getLocation().append("name.properties").toFile().toPath();
    }
    @After public void cleanup() throws Exception { if (project != null) project.delete(true,true,null); }
    @Test public void resolvesDefaultAndProjectRelativeAndExternalSettingsWithoutCreatingFiles() throws Exception {
        assertEquals(file,NameMappingsEditorDialog.resolvePath(project,""));
        assertEquals(file.getParent().resolve("config/types.properties"),NameMappingsEditorDialog.resolvePath(project,"config/types.properties"));
        Path external = Files.createTempDirectory("external-mappings-").resolve("types.properties");
        try { assertEquals(external,NameMappingsEditorDialog.resolvePath(project,external.toString())); }
        finally { Files.delete(external.getParent()); }
        assertFalse(Files.exists(file)); assertFalse(project.getFile("name.properties").exists());
    }
    @Test public void savedSettingsBecomeVisibleAndAvailableToTheProject() throws Exception {
        var settings = new NameMappingsFile(file); settings.save(settings.read(),".*Datetime$=java.time.LocalDateTime\n");
        TemplateWorkspace.refreshFile(file);
        IFile resource = project.getFile("name.properties"); assertTrue(resource.exists()); assertTrue(resource.isSynchronized(IResource.DEPTH_ZERO));
        assertEquals("java.time.LocalDateTime",io.github.domaresult.core.TypeNameMappings.read(file).resolve("example.Result","createdDatetime").orElseThrow());
    }
    @Test public void unsavedWorkspaceEditorIsPreservedBeforeAnExplicitSettingsSave() throws Exception {
        var settings = new NameMappingsFile(file); settings.save(settings.read(),"code=java.lang.String\n"); TemplateWorkspace.refreshDirectory(file.getParent());
        IFile resource = project.getFile("name.properties"); var manager = FileBuffers.getTextFileBufferManager();
        manager.connect(resource.getFullPath(),LocationKind.IFILE,null);
        try {
            var buffer = manager.getTextFileBuffer(resource.getFullPath(),LocationKind.IFILE); buffer.getDocument().set("code=java.lang.Long\n");
            GenerationException error = assertThrows(GenerationException.class,() -> TemplateWorkspace.ensureSaved(file,"settings file"));
            assertTrue(error.getMessage().contains("settings file"));
            assertEquals("code=java.lang.String\n",settings.read().source()); assertEquals("code=java.lang.Long\n",buffer.getDocument().get());
        } finally { manager.disconnect(resource.getFullPath(),LocationKind.IFILE,null); }
    }
    @Test public void refreshesNewNestedSettingsWithoutImportingUnrelatedProjectChanges() throws Exception {
        Path nested = file.getParent().resolve("config/types/name.properties");
        var settings = new NameMappingsFile(nested); settings.save(settings.read(),"code=java.lang.String\n");
        Path unrelated = file.getParent().resolve("unrelated.txt"); Files.writeString(unrelated,"outside edit");
        TemplateWorkspace.refreshFile(nested);
        assertTrue(project.getFile("config/types/name.properties").exists());
        assertTrue(project.getFile("config/types/name.properties").isSynchronized(IResource.DEPTH_ZERO));
        assertFalse(project.getFile("unrelated.txt").exists());
    }
}
