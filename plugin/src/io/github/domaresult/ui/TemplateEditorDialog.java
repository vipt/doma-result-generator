/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import io.github.domaresult.core.GenerationException;
import io.github.domaresult.core.TemplateFiles;
import io.github.domaresult.eclipse.TemplateWorkspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.dialogs.*;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/** A local UTF-8 editor. Only explicit Save/New/Copy actions change template files. */
final class TemplateEditorDialog extends TitleAreaDialog {
    private static final int SAVE = IDialogConstants.CLIENT_ID + 1;
    private final TemplateFiles files;
    private org.eclipse.swt.widgets.List names;
    private StyledText editor;
    private Label position;
    private TemplateFiles.Snapshot current;
    private boolean loading, dirty;

    TemplateEditorDialog(Shell parent, Path directory) {
        super(parent); files = new TemplateFiles(directory);
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }
    Path directory() { return files.directory(); }
    @Override protected Control createDialogArea(Composite parent) {
        setTitle("Edit doma-gen templates");
        setMessage("Save changes here, close this editor, then select Preview again in the generator.");
        Composite area = (Composite) super.createDialogArea(parent);
        Composite body = new Composite(area, SWT.NONE); body.setLayout(new GridLayout(2, false));
        body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Label directory = new Label(body, SWT.WRAP); directory.setText(files.directory().toString());
        GridData directoryData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1); directoryData.widthHint = 760; directory.setLayoutData(directoryData);
        Label note = new Label(body, SWT.WRAP);
        note.setText("Copy missing defaults adds editable Entity/DAO/Listener/SQL templates and lib.ftl; existing files are kept. Save/New/Copy writes are retained even if the generator is later canceled.");
        GridData noteData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1); noteData.widthHint = 760; note.setLayoutData(noteData);
        Composite left = new Composite(body, SWT.NONE); left.setLayout(new GridLayout());
        left.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, true));
        names = new org.eclipse.swt.widgets.List(left, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        GridData listData = new GridData(SWT.FILL, SWT.FILL, true, true); listData.widthHint = 230; names.setLayoutData(listData);
        button(left, "Copy missing defaults", this::copyDefaults);
        button(left, "New...", this::newTemplate);
        button(left, "Reload", this::reload);
        editor = new StyledText(body, SWT.BORDER | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
        editor.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true)); editor.setFont(JFaceResources.getTextFont()); editor.setTabs(4); editor.setEnabled(false);
        position = new Label(body, SWT.NONE); position.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        editor.addModifyListener(e -> {
            if (loading || current == null) return;
            dirty = !current.source().equals(editor.getText()); updateSave();
        });
        editor.addCaretListener(e -> updatePosition());
        names.addListener(SWT.Selection, e -> selectTemplate());
        try { refreshNames(null); }
        catch (IOException | GenerationException e) { error(e); }
        return area;
    }
    private static void button(Composite parent, String title, Runnable action) {
        Button button = new Button(parent, SWT.PUSH); button.setText(title);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false)); button.addListener(SWT.Selection, e -> action.run());
    }
    @Override protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, SAVE, "Save", false).setEnabled(dirty);
        createButton(parent, IDialogConstants.OK_ID, "Close", true);
    }
    @Override protected void buttonPressed(int id) { if (id == SAVE) save(); else super.buttonPressed(id); }
    private void refreshNames(String selected) throws IOException {
        List<String> available = files.list(); names.setItems(available.toArray(String[]::new));
        int index = selected == null ? -1 : available.indexOf(selected);
        if (index >= 0) names.setSelection(index);
        if (current == null && !available.isEmpty()) { int chosen = index >= 0 ? index : 0; names.select(chosen); load(available.get(chosen)); }
        if (available.isEmpty()) setMessage("No templates yet. Select Copy missing defaults to create editable copies, or New to add a template.");
    }
    private void selectTemplate() {
        String[] selection = names.getSelection();
        if (selection.length != 1 || current != null && current.name().equals(selection[0])) return;
        String next = selection[0];
        if (!canLeave()) { restoreSelection(); return; }
        try { load(next); }
        catch (IOException | GenerationException e) { restoreSelection(); error(e); }
    }
    private void restoreSelection() {
        names.deselectAll();
        if (current != null) for (int i = 0; i < names.getItemCount(); i++) if (current.name().equals(names.getItem(i))) names.select(i);
    }
    private void load(String name) throws IOException {
        TemplateWorkspace.ensureSaved(files.file(name));
        var snapshot = files.read(name);
        current = snapshot; loading = true;
        try { editor.setText(snapshot.source()); editor.setEnabled(true); }
        finally { loading = false; }
        dirty = false; updateSave(); updatePosition(); setErrorMessage(null);
        setMessage("Editing " + name + ". Save validates FreeMarker syntax; Preview checks the generated output.");
    }
    private boolean save() {
        if (current == null || !dirty) return true;
        try {
            TemplateWorkspace.ensureSaved(files.file(current.name()));
            current = files.save(current, editor.getText()); dirty = false; updateSave();
            refreshWorkspace(); setErrorMessage(null); setMessage("Saved " + current.name() + ". Select Preview again after closing this editor.");
            return true;
        } catch (IOException | GenerationException e) { error(e); return false; }
    }
    private boolean canLeave() {
        if (!dirty) return true;
        MessageDialog question = new MessageDialog(getShell(), "Unsaved template changes", null,
                "Save changes to " + current.name() + "?", MessageDialog.QUESTION, new String[]{"Save", "Discard", "Cancel"}, 0);
        int choice = question.open();
        if (choice == 0) return save();
        if (choice == 1) {
            loading = true;
            try { editor.setText(current.source()); }
            finally { loading = false; }
            dirty = false; updateSave(); return true;
        }
        return false;
    }
    private void copyDefaults() {
        try {
            List<String> copied = files.copyMissingDefaults();
            refreshWorkspace(); refreshNames(current == null ? null : current.name());
            setErrorMessage(null); setMessage(copied.size() + " standard templates copied. Existing files were kept.");
        } catch (IOException | GenerationException e) { error(e); }
    }
    private void newTemplate() {
        if (!canLeave()) return;
        InputDialog dialog = new InputDialog(getShell(), "New FTL template", "Relative .ftl file name (for example, selectAll.sql.ftl):", "", value -> {
            try { return Files.exists(files.file(value)) ? "A file with this name already exists." : null; }
            catch (IOException | IllegalArgumentException e) { return "Use a relative .ftl name inside the template directory."; }
        });
        if (dialog.open() != Window.OK) return;
        try {
            TemplateWorkspace.ensureSaved(files.file(dialog.getValue()));
            files.create(dialog.getValue()); refreshWorkspace();
            current = null; refreshNames(dialog.getValue());
            editor.setFocus();
        } catch (IOException | GenerationException e) { error(e); }
    }
    private void reload() {
        if (!canLeave()) return;
        try {
            String selected = current == null ? null : current.name();
            refreshNames(selected);
            if (selected != null) load(selected);
        } catch (IOException | GenerationException e) { error(e); }
    }
    private void refreshWorkspace() {
        try { TemplateWorkspace.refreshDirectory(files.directory()); }
        catch (IOException | CoreException e) { MessageDialog.openWarning(getShell(), "Template files", "Template files were saved, but the workspace could not be refreshed. Refresh the template folder in Explorer."); }
    }
    private void updateSave() {
        if (getButton(SAVE) != null) getButton(SAVE).setEnabled(dirty);
        updatePosition();
    }
    private void updatePosition() {
        if (position == null) return;
        int offset = editor.getCaretOffset(), line = editor.getLineAtOffset(offset);
        position.setText((current == null ? "No file selected" : current.name() + (dirty ? " *" : ""))
                + "    Line " + (line + 1) + ", column " + (offset - editor.getOffsetAtLine(line) + 1) + "    UTF-8");
    }
    private void error(Exception error) {
        setErrorMessage(error instanceof GenerationException ? error.getMessage()
                : "Cannot read or save the template. Check its UTF-8 encoding, path and file permissions; reload after external changes.");
    }
    @Override public boolean close() { return !canLeave() ? false : super.close(); }
    @Override protected Point getInitialSize() { return new Point(1000, 760); }
}
