/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import io.github.domaresult.core.GenerationException;
import io.github.domaresult.core.TableGenerator.Artifact;
import io.github.domaresult.core.TableGenerator.Kind;
import io.github.domaresult.eclipse.TableWorkspaceUpdater;
import io.github.domaresult.eclipse.TableWorkspaceUpdater.*;
import java.io.IOException;
import java.util.*;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;

/** Checkboxes define the only files that may be compared or written. Row selection is separate. */
final class ProposedFilesModel {
    record Destination(Artifact artifact, IFile file, IContainer outputRoot) {}
    static final class Entry {
        final Destination destination;
        private boolean checked = true;
        private boolean existing;
        private Snapshot snapshot;
        private Action action = Action.MERGE;
        private Plan plan;
        private String error;
        private final Map<Action, Outcome> cached = new EnumMap<>(Action.class);
        Entry(Destination destination) { this.destination = destination; existing = destination.file().exists(); }
        boolean checked() { return checked; }
        boolean existing() { return existing; }
        Action action() { return action; }
        Plan plan() { return plan; }
        String error() { return error; }
        String operation() { return !checked ? "Skip" : !existing ? "Create" : action == Action.OVERWRITE ? "Overwrite" : "Merge"; }
        String status() {
            if (!checked) return error == null ? "Skipped" : "Skipped (error)";
            return error != null ? "Conflict / error" : plan != null && plan.changed() ? "Changes" : "Unchanged";
        }
        String source() { return plan == null ? destination.artifact().source() : plan.after(); }
        String note() { return error != null ? error : plan == null ? "" : plan.note(); }
    }
    private record Outcome(Plan plan, String error) {}
    static final class Node {
        final String label;
        final Node parent;
        final Entry entry;
        private final Map<String, Node> children = new TreeMap<>();
        Node(String label, Node parent, Entry entry) { this.label = label; this.parent = parent; this.entry = entry; }
        List<Node> children() { return List.copyOf(children.values()); }
        List<Entry> leaves() {
            if (entry != null) return List.of(entry);
            return children.values().stream().flatMap(child -> child.leaves().stream()).toList();
        }
    }
    private final TableWorkspaceUpdater updater;
    private final List<Entry> entries = new ArrayList<>();
    private final Node root = new Node("", null, null);
    private long revision;
    private long reviewedRevision = -1;

    ProposedFilesModel(TableWorkspaceUpdater updater, List<Destination> destinations) {
        this(updater, destinations, () -> {});
    }
    ProposedFilesModel(TableWorkspaceUpdater updater, List<Destination> destinations, Runnable cancellation) {
        this.updater = updater;
        Set<IFile> paths = new HashSet<>();
        for (Destination destination : destinations) {
            cancellation.run();
            if (!paths.add(destination.file())) throw new GenerationException("Two generated files have the same destination.");
            if (!destination.outputRoot().getFile(new org.eclipse.core.runtime.Path(destination.artifact().path())).equals(destination.file())) throw new GenerationException("A generated file must be inside its selected output folder with the generated relative path.");
            Entry entry = new Entry(destination); entries.add(entry); prepare(entry); add(entry);
        }
        cancellation.run();
    }
    List<Entry> entries() { return List.copyOf(entries); }
    List<Node> roots() { return root.children(); }
    long revision() { return revision; }
    int checkedCount() { return (int) entries.stream().filter(Entry::checked).count(); }
    int checkedExistingCount() { return (int) entries.stream().filter(e -> e.checked && e.existing).count(); }
    int checkedErrors() { return (int) entries.stream().filter(e -> e.checked && e.plan == null).count(); }
    boolean isChecked(Node node) { return node.leaves().stream().anyMatch(Entry::checked); }
    boolean isGrayed(Node node) { var leaves = node.leaves(); return leaves.stream().anyMatch(Entry::checked) && leaves.stream().anyMatch(e -> !e.checked); }

    void setChecked(Node node, boolean checked) { setChecked(node.leaves(), checked); }
    void setAllChecked(boolean checked) { setChecked(entries, checked); }
    private void setChecked(List<Entry> targets, boolean checked) {
        boolean changed = false;
        for (Entry entry : targets) if (entry.checked != checked) { entry.checked = checked; changed = true; }
        if (changed) revision++;
    }
    void setAction(Entry entry, Action action) {
        requireAction(action);
        if (!entry.checked || !entry.existing || entry.action == action) return;
        entry.action = action; prepare(entry); revision++;
    }
    void applyToChecked(Action action) {
        requireAction(action);
        for (Entry entry : entries) setAction(entry, action);
    }
    private static void requireAction(Action action) {
        if (action != Action.MERGE && action != Action.OVERWRITE) throw new GenerationException("Choose Merge or Overwrite; uncheck a file to skip it.");
    }
    boolean validSelection() { return checkedCount() > 0 && checkedErrors() == 0; }
    boolean hasChanges() { return validSelection() && entries.stream().anyMatch(e -> e.checked && e.plan.changed()); }
    List<Plan> selectedPlans() {
        if (!validSelection()) throw new GenerationException("Check at least one file and resolve or uncheck files with conflicts/errors.");
        return entries.stream().filter(Entry::checked).map(Entry::plan).toList();
    }
    void markCompared(long comparedRevision) {
        if (comparedRevision == revision && hasChanges()) reviewedRevision = revision;
    }
    void invalidateReview() { reviewedRevision = -1; }
    boolean canApply() { return hasChanges() && reviewedRevision == revision; }

    private void prepare(Entry entry) {
        if (entry.cached.containsKey(entry.action)) {
            Outcome cached = entry.cached.get(entry.action); entry.plan = cached.plan(); entry.error = cached.error(); return;
        }
        entry.plan = null; entry.error = null;
        try {
            if (entry.snapshot == null) {
                entry.snapshot = updater.snapshot(entry.destination.file());
                entry.existing = entry.snapshot.source() != null;
            }
            entry.plan = updater.plan(entry.destination.artifact(), entry.snapshot, entry.action);
        } catch (GenerationException e) { entry.error = e.getMessage(); }
        catch (IOException e) { entry.error = "Cannot read the generation baseline. Check workspace permissions and refresh Preview."; }
        catch (CoreException e) { entry.error = "Cannot read the saved file. Check permissions, refresh the resource and select Preview again."; }
        entry.cached.put(entry.action, new Outcome(entry.plan, entry.error));
    }
    private void add(Entry entry) {
        IContainer output = entry.destination.outputRoot();
        String folder = output.getProjectRelativePath().toPortableString();
        Node parent = group(root, "root:" + output.getFullPath().toPortableString(), folder.isEmpty() ? "<project root>" : folder);
        String path = entry.destination.artifact().path();
        String[] parts = path.split("/");
        if (entry.destination.artifact().kind() != Kind.SQL) {
            if (parts.length > 1) {
                String pkg = String.join(".", Arrays.copyOf(parts, parts.length - 1));
                parent = group(parent, "package:" + pkg, pkg);
            }
        } else {
            // SQL resources retain META-INF, then compact the Java package before the DAO folder.
            if (parts.length >= 3 && parts[0].equals("META-INF")) {
                parent = group(parent, "folder:META-INF", "META-INF");
                if (parts.length > 3) {
                    String pkg = String.join(".", Arrays.copyOfRange(parts, 1, parts.length - 2));
                    parent = group(parent, "package:" + pkg, pkg);
                }
                parent = group(parent, "folder:" + parts[parts.length - 2], parts[parts.length - 2]);
            } else for (int i = 0; i < parts.length - 1; i++) parent = group(parent, "folder:" + parts[i], parts[i]);
        }
        String name = parts[parts.length - 1];
        parent.children.put("file:" + name, new Node(name, parent, entry));
    }
    private static Node group(Node parent, String key, String label) {
        return parent.children.computeIfAbsent(key, unused -> new Node(label, parent, null));
    }
}
