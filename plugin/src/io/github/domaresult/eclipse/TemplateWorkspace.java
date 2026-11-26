/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.eclipse;

import io.github.domaresult.core.GenerationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.CoreException;

/** Coordinates explicit FTL saves with workspace and externally opened Eclipse text buffers. */
public final class TemplateWorkspace {
    private TemplateWorkspace() {}
    public static void ensureSaved(Path file) throws IOException {
        Set<Path> paths = new LinkedHashSet<>(); paths.add(file.toAbsolutePath().normalize());
        if (Files.exists(file)) paths.add(file.toRealPath());
        for (Path path : paths) {
            var manager = FileBuffers.getTextFileBufferManager();
            var external = manager.getTextFileBuffer(org.eclipse.core.runtime.Path.fromOSString(path.toString()), LocationKind.LOCATION);
            if (external != null && external.isDirty()) throw dirty();
            for (IFile resource : ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(path.toUri())) {
                var buffer = manager.getTextFileBuffer(resource.getFullPath(), LocationKind.IFILE);
                if (buffer != null && buffer.isDirty()) throw dirty();
            }
        }
    }
    private static GenerationException dirty() {
        return new GenerationException("This template has unsaved edits in an Eclipse editor. Save or revert that editor, then reload the template before saving here.");
    }
    public static void refreshDirectory(Path directory) throws IOException, CoreException {
        Path absolute = directory.toAbsolutePath().normalize();
        if (Files.exists(absolute)) absolute = absolute.toRealPath();
        // Find the nearest workspace container, then refresh just the template directory.
        var root = ResourcesPlugin.getWorkspace().getRoot();
        for (Path cursor = absolute; cursor != null; cursor = cursor.getParent()) {
            IContainer[] containers = root.findContainersForLocationURI(cursor.toUri());
            if (containers.length == 0) continue;
            String relative = cursor.relativize(absolute).toString();
            for (IContainer container : containers) {
                IResource resource = relative.isEmpty() ? container : container.getFolder(org.eclipse.core.runtime.Path.fromOSString(relative));
                resource.refreshLocal(IResource.DEPTH_INFINITE, null);
            }
            return;
        }
    }
}
