/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;

/** Snapshot-based UTF-8 editing for explicitly selected generator settings files. */
public final class Utf8SettingsFile {
    public record Snapshot(String source, FileTime modified) {
        public boolean exists() { return source != null; }
    }
    private final Path path;
    public Utf8SettingsFile(Path path) { this.path = path.toAbsolutePath().normalize(); }
    public Path path() { return path; }

    public Snapshot read() throws IOException {
        if (Files.isSymbolicLink(path)) throw new GenerationException("Select the real settings file instead of a symbolic link.");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new Snapshot(null, null);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new GenerationException("Select a settings file, not a directory.");
        FileTime modified = Files.getLastModifiedTime(path);
        String source = Files.readString(path, StandardCharsets.UTF_8);
        if (!modified.equals(Files.getLastModifiedTime(path))) throw changed();
        return new Snapshot(source, modified);
    }
    public Snapshot save(Snapshot before, String source) throws IOException {
        verify(before);
        if (!before.exists()) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, source, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            return read();
        }
        if (!Files.isWritable(path)) throw new IOException("The settings file is read-only.");
        Path temporary = Files.createTempFile(path.getParent(), ".doma-settings-", ".tmp");
        try {
            Files.writeString(temporary, source, StandardCharsets.UTF_8);
            try { Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(path)); }
            catch (UnsupportedOperationException ignored) { /* Non-POSIX file system. */ }
            verify(before);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        return read();
    }
    private void verify(Snapshot before) throws IOException {
        if (!before.equals(read())) throw changed();
    }
    private static GenerationException changed() {
        return new GenerationException("The settings file changed on disk. Reload it and review the latest contents before saving.");
    }
}
