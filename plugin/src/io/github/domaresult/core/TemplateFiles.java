/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;

/** Explicit template-file edits are independent of generated-source previews and database access. */
public final class TemplateFiles {
    public record Snapshot(String name, String source, FileTime modified) {}
    private final Path directory;
    public TemplateFiles(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }
    public Path directory() { return directory; }

    public List<String> list() throws IOException {
        if (!Files.exists(directory)) return List.of();
        if (!Files.isDirectory(directory)) throw new GenerationException("The template path must be a directory.");
        Path root = directory.toRealPath();
        try (var files = Files.walk(root, 16)) {
            return files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> p.getFileName().toString().endsWith(".ftl"))
                    .map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
    }
    public Path file(String name) throws IOException {
        Path relative = Path.of(name);
        if (name.isBlank() || name.chars().anyMatch(Character::isISOControl) || !name.endsWith(".ftl") || relative.isAbsolute() || name.contains("\\")
                || relative.startsWith("..") || !relative.normalize().equals(relative)) throw new GenerationException("Use a relative .ftl file name inside the template directory.");
        Path target = directory.resolve(relative);
        if (!target.startsWith(directory)) throw new GenerationException("Use a template file inside the selected directory.");
        for (Path p = target; p != null && !p.equals(directory); p = p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new GenerationException("Edit the real template file instead of a symbolic link.");
        }
        return target;
    }
    public Snapshot read(String name) throws IOException {
        Path file = file(name);
        FileTime modified = Files.getLastModifiedTime(file);
        String source = Files.readString(file, StandardCharsets.UTF_8);
        if (!modified.equals(Files.getLastModifiedTime(file))) throw new GenerationException("The template changed while loading. Reload it before editing.");
        return new Snapshot(name, source, modified);
    }
    public Snapshot save(Snapshot before, String source) throws IOException {
        DomaGenTemplates.validate(before.name(), source);
        Path file = file(before.name());
        verify(before);
        if (!Files.isWritable(file)) throw new IOException("The template is read-only.");
        Path temporary = Files.createTempFile(file.getParent(), ".doma-ftl-", ".tmp");
        try {
            Files.writeString(temporary, source, StandardCharsets.UTF_8);
            try { Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(file)); }
            catch (UnsupportedOperationException ignored) { /* Non-POSIX file system. */ }
            verify(before);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        return read(before.name());
    }
    private void verify(Snapshot before) throws IOException {
        if (!Files.isRegularFile(file(before.name()), LinkOption.NOFOLLOW_LINKS)) throw changed();
        Snapshot current = read(before.name());
        if (!before.modified().equals(current.modified()) || !before.source().equals(current.source())) throw changed();
    }
    private static GenerationException changed() { return new GenerationException("The template changed on disk. Reload it and review the latest contents before saving."); }

    /** Add only absent files, including an intentionally empty lib.ftl; never replace custom templates. */
    public List<String> copyMissingDefaults() throws IOException {
        Files.createDirectories(directory);
        List<String> copied = new ArrayList<>();
        for (String name : DomaGenTemplates.NAMES) {
            Path file = file(name);
            try {
                Files.writeString(file, DomaGenTemplates.source(name), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                copied.add(name);
            } catch (FileAlreadyExistsException ignored) { /* The user's file is authoritative. */ }
        }
        return List.copyOf(copied);
    }
    public Snapshot create(String name) throws IOException {
        Path file = file(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return read(name);
    }
}
