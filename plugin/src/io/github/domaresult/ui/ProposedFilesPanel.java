/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import io.github.domaresult.eclipse.TableWorkspaceUpdater.Action;
import io.github.domaresult.ui.ProposedFilesModel.*;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/** Left: checked file tree and batch actions. Right: selected file's read-only proposal. */
final class ProposedFilesPanel extends Composite {
    private final Runnable changed;
    private final CheckboxTreeViewer tree;
    private final Label summary, path, note;
    private final StyledText source;
    private final Combo action;
    private final Button mergeChecked, overwriteChecked;
    private ProposedFilesModel model;
    private boolean selectingAction;

    ProposedFilesPanel(Composite parent, Runnable changed) {
        super(parent, SWT.NONE); this.changed = changed;
        setLayout(new GridLayout());
        Label instruction = new Label(this, SWT.WRAP);
        instruction.setText("Check the files to generate/update. Unchecked files are skipped. Merge/Overwrite applies only to checked existing files.");
        GridData instructionData = new GridData(SWT.FILL, SWT.CENTER, true, false); instructionData.widthHint = 850; instruction.setLayoutData(instructionData);
        summary = new Label(this, SWT.NONE); summary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        SashForm split = new SashForm(this, SWT.HORIZONTAL | SWT.SMOOTH);
        split.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true)); split.setSashWidth(6);
        Composite left = new Composite(split, SWT.NONE); left.setLayout(new GridLayout());
        Composite checks = new Composite(left, SWT.NONE); checks.setLayout(new GridLayout(4, false));
        checks.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        button(checks, "Check all", () -> { if (model != null) { model.setAllChecked(true); refresh(); } });
        button(checks, "Uncheck all", () -> { if (model != null) { model.setAllChecked(false); refresh(); } });
        button(checks, "Expand", this::expand);
        button(checks, "Collapse", this::collapse);
        Composite actions = new Composite(left, SWT.NONE); actions.setLayout(new GridLayout(2, false));
        actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        mergeChecked = button(actions, "Merge checked", () -> applyChecked(Action.MERGE));
        overwriteChecked = button(actions, "Overwrite checked", () -> applyChecked(Action.OVERWRITE));
        tree = new CheckboxTreeViewer(left, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.H_SCROLL | SWT.V_SCROLL);
        tree.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        tree.getTree().setHeaderVisible(true); tree.getTree().setLinesVisible(true);
        String[] columns = {"File", "Action", "Status"}; int[] widths = {240, 85, 115};
        for (int i = 0; i < columns.length; i++) {
            final int index = i;
            TreeViewerColumn column = new TreeViewerColumn(tree, SWT.NONE); column.getColumn().setText(columns[i]); column.getColumn().setWidth(widths[i]);
            column.setLabelProvider(new ColumnLabelProvider() {
                @Override public String getText(Object value) {
                    Node node = (Node) value;
                    if (index == 0) return node.label;
                    if (node.entry != null) return index == 1 ? node.entry.operation() : node.entry.status();
                    return index == 1 ? "" : node.leaves().stream().filter(Entry::checked).count() + "/" + node.leaves().size() + " checked";
                }
            });
        }
        tree.setContentProvider(new ITreeContentProvider() {
            @Override public Object[] getElements(Object input) { return input instanceof ProposedFilesModel value ? value.roots().toArray() : new Object[0]; }
            @Override public Object[] getChildren(Object parent) { return parent instanceof Node node ? node.children().toArray() : getElements(parent); }
            @Override public Object getParent(Object child) { Node parent = child instanceof Node node ? node.parent : null; return parent == null || parent.parent == null ? null : parent; }
            @Override public boolean hasChildren(Object parent) { return getChildren(parent).length > 0; }
        });
        tree.setCheckStateProvider(new ICheckStateProvider() {
            @Override public boolean isChecked(Object element) { return model != null && model.isChecked((Node) element); }
            @Override public boolean isGrayed(Object element) { return model != null && model.isGrayed((Node) element); }
        });
        tree.addCheckStateListener(e -> { if (model != null) { model.setChecked((Node) e.getElement(), e.getChecked()); refresh(); } });
        tree.addSelectionChangedListener(e -> showSelected());
        Composite right = new Composite(split, SWT.NONE); right.setLayout(new GridLayout());
        path = new Label(right, SWT.WRAP); GridData pathData = new GridData(SWT.FILL, SWT.CENTER, true, false); pathData.widthHint = 440; path.setLayoutData(pathData);
        Composite fileAction = new Composite(right, SWT.NONE); fileAction.setLayout(new GridLayout(2, false));
        fileAction.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        new Label(fileAction, SWT.NONE).setText("Selected file action");
        action = new Combo(fileAction, SWT.READ_ONLY); action.setItems(new String[]{"Merge", "Overwrite"});
        action.addListener(SWT.Selection, e -> {
            Node node = selectedNode();
            if (!selectingAction && model != null && node != null && node.entry != null && action.getSelectionIndex() >= 0) {
                model.setAction(node.entry, action.getSelectionIndex() == 0 ? Action.MERGE : Action.OVERWRITE); refresh();
            }
        });
        note = new Label(right, SWT.WRAP); GridData noteData = new GridData(SWT.FILL, SWT.CENTER, true, false); noteData.widthHint = 440; note.setLayoutData(noteData);
        source = new StyledText(right, SWT.BORDER | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.READ_ONLY);
        source.setFont(JFaceResources.getTextFont()); source.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        split.setWeights(42, 58); setModel(null);
    }
    private static Button button(Composite parent, String title, Runnable work) {
        Button button = new Button(parent, SWT.PUSH); button.setText(title); button.addListener(SWT.Selection, e -> work.run()); return button;
    }
    private void expand() { tree.expandAll(); }
    private void collapse() { tree.collapseAll(); }
    void setModel(ProposedFilesModel model) {
        this.model = model; tree.setInput(model);
        if (model != null && !model.roots().isEmpty()) {
            tree.expandToLevel(3);
            Node first = model.roots().get(0);
            while (first.entry == null && !first.children().isEmpty()) first = first.children().get(0);
            tree.setSelection(new StructuredSelection(first), true);
        }
        refresh();
    }
    private void applyChecked(Action action) { if (model != null) { model.applyToChecked(action); refresh(); } }
    private Node selectedNode() {
        return tree.getStructuredSelection().getFirstElement() instanceof Node node ? node : null;
    }
    private void refresh() {
        tree.refresh();
        summary.setText(model == null ? "Select tables and choose Preview."
                : model.checkedCount() + "/" + model.entries().size() + " checked; " + model.checkedExistingCount() + " existing; " + model.checkedErrors() + " checked conflicts/errors");
        boolean existing = model != null && model.checkedExistingCount() > 0;
        mergeChecked.setEnabled(existing); overwriteChecked.setEnabled(existing);
        showSelected(); changed.run();
    }
    private void showSelected() {
        // Selection events can fire while controls are being initialized.
        if (source == null) return;
        Node node = selectedNode(); selectingAction = true;
        try {
            action.deselectAll(); action.setEnabled(false);
            if (model == null || node == null) {
                path.setText("Select a file to view its proposed contents."); note.setText(""); source.setText("");
            } else if (node.entry == null) {
                path.setText(node.label); note.setText(node.leaves().stream().filter(Entry::checked).count() + "/" + node.leaves().size() + " files checked. Select a file to view its contents."); source.setText("");
            } else {
                Entry entry = node.entry; path.setText(entry.destination.file().getProjectRelativePath().toPortableString());
                if (entry.existing()) action.select(entry.action() == Action.OVERWRITE ? 1 : 0);
                action.setEnabled(entry.checked() && entry.existing());
                note.setText((entry.checked() ? "" : "Skipped. Check this file to include it. ")
                        + (!entry.existing() && entry.checked() ? "Create. " : "") + entry.note());
                source.setText(entry.source());
            }
        } finally { selectingAction = false; }
        note.getParent().layout();
    }
}
