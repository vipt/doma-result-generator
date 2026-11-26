/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.eclipse;

import io.github.domaresult.core.GenerationException;
import io.github.domaresult.core.TableGenerator.Artifact;
import io.github.domaresult.core.TableGenerator.Kind;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.jdt.core.JavaCore;
import org.osgi.framework.FrameworkUtil;

/** Plans all files first; rejects stale/dirty inputs before any workspace write. */
public final class TableWorkspaceUpdater {
    public enum Action { MERGE, OVERWRITE, KEEP }
    public record Snapshot(IFile file, String source, long stamp, String charset) {}
    public record Plan(Artifact artifact, Snapshot snapshot, Action action, String after, String note) {
        public boolean changed() { return !Objects.equals(snapshot.source(), after); }
    }
    private final Path baselines;
    public TableWorkspaceUpdater() {
        this(Platform.getStateLocation(FrameworkUtil.getBundle(TableWorkspaceUpdater.class)).append("table-baselines").toFile().toPath());
    }
    public TableWorkspaceUpdater(Path baselines) { this.baselines = baselines; }

    public Snapshot snapshot(IFile file) throws CoreException {
        if (dirty(file)) throw new GenerationException("Save unsaved edits before Preview: " + file.getFullPath());
        if (!file.isSynchronized(IResource.DEPTH_ZERO)) throw new GenerationException("Refresh the file from disk before Preview: " + file.getFullPath());
        long stamp = file.getModificationStamp();
        String charset = file.exists() ? file.getCharset() : StandardCharsets.UTF_8.name();
        String text = null;
        if (file.exists()) {
            try (var stream = file.getContents()) {
                text = Charset.forName(charset).newDecoder().decode(ByteBuffer.wrap(stream.readAllBytes())).toString();
            } catch (IOException e) { throw new GenerationException("Cannot read the target in its character encoding: " + file.getFullPath()); }
        }
        if (stamp != file.getModificationStamp()) throw new GenerationException("The file changed while reading. Refresh Preview.");
        return new Snapshot(file, text, stamp, charset);
    }

    public Plan plan(Artifact artifact, Snapshot before, Action action) throws IOException {
        if (action == Action.KEEP && before.source() != null) return new Plan(artifact, before, action, before.source(), "Kept unchanged");
        String after = artifact.source(), note = "New file";
        if (before.source() != null) {
            if (action == Action.OVERWRITE) {
                after = TableFileMerger.lineEndings(before.source(), after);
                note = "Overwrite: existing custom code/comments will be removed";
            } else {
                String baseline = readBaseline(before.file());
                var merged = new TableFileMerger().merge(before.source(), after, baseline, artifact.kind() != Kind.SQL);
                after = merged.source(); note = merged.note();
            }
        }
        if (artifact.kind() != Kind.SQL) TableFileMerger.parse(after);
        return new Plan(artifact, before, action, after, note);
    }

    public void apply(List<Plan> plans, IProgressMonitor monitor) throws CoreException, IOException {
        if (plans.isEmpty()) return;
        IProject project = plans.get(0).snapshot().file().getProject();
        if (plans.stream().anyMatch(p -> !p.snapshot().file().getProject().equals(project))) throw new GenerationException("All targets must be in the selected project.");
        Map<IFile, byte[]> encoded = new LinkedHashMap<>();
        Set<IFile> seen = new HashSet<>();
        for (Plan plan : plans) {
            if (!seen.add(plan.snapshot().file())) throw new GenerationException("Two generated files have the same destination.");
            if (plan.changed()) encoded.put(plan.snapshot().file(), encode(plan.after(), plan.snapshot().charset()));
        }
        ResourcesPlugin.getWorkspace().run(progress -> {
            for (Plan plan : plans) verify(plan.snapshot());
            if (progress.isCanceled()) throw new OperationCanceledException();
            IStatus edit = ResourcesPlugin.getWorkspace().validateEdit(encoded.keySet().toArray(IFile[]::new), null);
            if (!edit.isOK()) throw new CoreException(edit);
            List<Plan> written = new ArrayList<>();
            List<IFolder> folders = new ArrayList<>();
            try {
                for (Plan plan : plans) {
                    if (!plan.changed()) continue;
                    IFile file = plan.snapshot().file();
                    createParents(file.getParent(), folders, progress);
                    written.add(plan);
                    var stream = new ByteArrayInputStream(encoded.get(file));
                    if (file.exists()) file.setContents(stream, IResource.KEEP_HISTORY, progress);
                    else {
                        file.create(stream, false, progress);
                        file.setCharset(StandardCharsets.UTF_8.name(), progress);
                    }
                }
            } catch (CoreException | RuntimeException failure) {
                // Resource writes are not transactional. Restore contents if a later write fails.
                Collections.reverse(written);
                for (Plan plan : written) {
                    try {
                        IFile file = plan.snapshot().file();
                        if (plan.snapshot().source() == null) { if (file.exists()) file.delete(true, new NullProgressMonitor()); }
                        else file.setContents(new ByteArrayInputStream(encode(plan.snapshot().source(), plan.snapshot().charset())),
                                IResource.FORCE | IResource.KEEP_HISTORY, new NullProgressMonitor());
                    } catch (CoreException | RuntimeException restore) { failure.addSuppressed(restore); }
                }
                Collections.reverse(folders);
                for (IFolder folder : folders) {
                    try { if (folder.exists() && folder.members().length == 0) folder.delete(true, new NullProgressMonitor()); }
                    catch (CoreException restore) { failure.addSuppressed(restore); }
                }
                throw failure;
            }
        }, project, IWorkspace.AVOID_UPDATE, monitor);
        // Store only generator output, never merged custom code. This is workspace-local state.
        Files.createDirectories(baselines);
        for (Plan plan : plans) {
            if (plan.action() == Action.KEEP || plan.snapshot().source() != null && plan.artifact().kind() == Kind.SQL
                    && plan.action() == Action.MERGE && readBaseline(plan.snapshot().file()) == null) continue;
            Path destination = baselinePath(plan.snapshot().file());
            Path temporary = Files.createTempFile(baselines, "baseline-", ".tmp");
            try {
                Files.writeString(temporary, plan.artifact().source(), StandardCharsets.UTF_8);
                Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        }
    }

    private void verify(Snapshot snapshot) throws CoreException {
        Snapshot current = snapshot(snapshot.file());
        if (snapshot.stamp() != current.stamp() || !Objects.equals(snapshot.source(), current.source())
                || !snapshot.charset().equals(current.charset())) throw new GenerationException("A file changed after Preview. Refresh Preview before generating: " + snapshot.file().getFullPath());
    }
    private static boolean dirty(IFile file) throws CoreException {
        var buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
        if (buffer != null && buffer.isDirty()) return true;
        return file.exists() && "java".equalsIgnoreCase(file.getFileExtension()) && JavaCore.create(file) instanceof org.eclipse.jdt.core.ICompilationUnit unit && unit.hasUnsavedChanges();
    }
    private static void createParents(IContainer parent, List<IFolder> created, IProgressMonitor monitor) throws CoreException {
        if (parent.exists()) return;
        if (!(parent instanceof IFolder folder)) throw new GenerationException("Select an accessible destination folder in this project.");
        createParents(parent.getParent(), created, monitor);
        folder.create(false, true, monitor);
        created.add(folder);
    }
    private static byte[] encode(String text, String charset) {
        try {
            ByteBuffer buffer = Charset.forName(charset).newEncoder().encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); return bytes;
        } catch (CharacterCodingException e) { throw new GenerationException("A generated file cannot be encoded in the target character encoding. No files were written."); }
    }
    private String readBaseline(IFile file) throws IOException {
        Path path = baselinePath(file);
        return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
    }
    private Path baselinePath(IFile file) {
        try {
            String key = file.getLocationURI().toString();
            return baselines.resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))) + ".txt");
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
