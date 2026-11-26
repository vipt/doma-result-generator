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

/** Coordinates FTL/name.properties saves with workspace and external Eclipse text buffers. */
public final class TemplateWorkspace {
    private TemplateWorkspace() {}
    public static void ensureSaved(Path file) throws IOException {
        ensureSaved(file, "template");
    }
    public static void ensureSaved(Path file, String label) throws IOException {
        Set<Path> paths = new LinkedHashSet<>(); paths.add(file.toAbsolutePath().normalize());
        if (Files.exists(file)) paths.add(file.toRealPath());
        for (Path path : paths) {
            var manager = FileBuffers.getTextFileBufferManager();
            var external = manager.getTextFileBuffer(org.eclipse.core.runtime.Path.fromOSString(path.toString()), LocationKind.LOCATION);
            if (external != null && external.isDirty()) throw dirty(label);
            for (IFile resource : ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(path.toUri())) {
                var buffer = manager.getTextFileBuffer(resource.getFullPath(), LocationKind.IFILE);
                if (buffer != null && buffer.isDirty()) throw dirty(label);
            }
        }
    }
    private static GenerationException dirty(String label) {
        return new GenerationException("This " + label + " has unsaved edits in an Eclipse editor. Save or revert that editor, then reload it before saving here.");
    }
    /** Refresh just one settings file, without recursively rescanning a large project. */
    public static void refreshFile(Path file) throws IOException, CoreException {
        Path absolute = file.toAbsolutePath().normalize();
        if (Files.exists(absolute)) absolute = absolute.toRealPath();
        var root = ResourcesPlugin.getWorkspace().getRoot();
        for (Path cursor = absolute.getParent(); cursor != null; cursor = cursor.getParent()) {
            IContainer[] containers = root.findContainersForLocationURI(cursor.toUri());
            if (containers.length == 0) continue;
            Path relative = cursor.relativize(absolute);
            for (IContainer container : containers) {
                IContainer parent = container;
                for (int i = 0; i < relative.getNameCount() - 1; i++) {
                    IFolder folder = parent.getFolder(new org.eclipse.core.runtime.Path(relative.getName(i).toString()));
                    if (!folder.exists()) folder.refreshLocal(IResource.DEPTH_ZERO, null);
                    parent = folder;
                }
                parent.getFile(new org.eclipse.core.runtime.Path(relative.getFileName().toString())).refreshLocal(IResource.DEPTH_ZERO, null);
            }
            return;
        }
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
