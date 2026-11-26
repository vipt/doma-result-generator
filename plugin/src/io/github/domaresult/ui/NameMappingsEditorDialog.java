/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import io.github.domaresult.core.GenerationException;
import io.github.domaresult.core.NameMappingsFile;
import io.github.domaresult.core.Utf8SettingsFile;
import io.github.domaresult.eclipse.TemplateWorkspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.dialogs.*;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/** Explicit editing/creation of the selected UTF-8 name.properties without a DB connection. */
final class NameMappingsEditorDialog extends TitleAreaDialog {
    private static final int SAVE = IDialogConstants.CLIENT_ID + 1;
    private static final int RELOAD = IDialogConstants.CLIENT_ID + 2;
    private final NameMappingsFile file;
    private Utf8SettingsFile.Snapshot current;
    private StyledText editor;
    private Label position;
    private boolean loading, dirty;

    static Path edit(Shell parent, IProject project, String configured) {
        Path path = resolvePath(project, configured);
        new NameMappingsEditorDialog(parent, path).open();
        return Files.isRegularFile(path) ? path : null;
    }
    static Path resolvePath(IProject project, String configured) {
        Path path = configured.isBlank() ? Path.of("name.properties") : Path.of(configured.trim());
        if (!path.isAbsolute()) {
            if (project.getLocation() == null) throw new GenerationException("Select a local name.properties file first.");
            path = project.getLocation().toFile().toPath().resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }
    NameMappingsEditorDialog(Shell parent, Path path) {
        super(parent); file = new NameMappingsFile(path); setShellStyle(getShellStyle() | SWT.RESIZE);
    }
    @Override protected Control createDialogArea(Composite parent) {
        setTitle("Edit name.properties");
        setMessage("Edit rules, Save, then close and select Preview again. No database connection is needed.");
        Composite area = (Composite) super.createDialogArea(parent);
        Composite body = new Composite(area, SWT.NONE); body.setLayout(new GridLayout());
        body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Label path = new Label(body, SWT.WRAP); path.setText(file.path().toString());
        GridData pathData = new GridData(SWT.FILL, SWT.CENTER, true, false); pathData.widthHint = 730; path.setLayoutData(pathData);
        Label note = new Label(body, SWT.WRAP);
        note.setText("Example: .*Datetime$=java.time.LocalDateTime (no spaces around '=')\nRules match generated Java property names; the first match wins. Save checks regular expressions and Java type names. Explicit saves remain if the generator is later canceled.");
        GridData noteData = new GridData(SWT.FILL, SWT.CENTER, true, false); noteData.widthHint = 730; note.setLayoutData(noteData);
        editor = new StyledText(body, SWT.BORDER | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
        editor.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true)); editor.setFont(JFaceResources.getTextFont()); editor.setTabs(4); editor.setEnabled(false);
        position = new Label(body, SWT.NONE); position.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        editor.addModifyListener(e -> {
            if (loading || current == null) return;
            dirty = !initialText().equals(editor.getText()); updateSave();
        });
        editor.addCaretListener(e -> updatePosition());
        load();
        return area;
    }
    @Override protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, RELOAD, "Reload", false);
        createButton(parent, SAVE, "Save", false);
        createButton(parent, IDialogConstants.OK_ID, "Close", true); updateSave();
    }
    @Override protected void buttonPressed(int id) {
        if (id == SAVE) save();
        else if (id == RELOAD) { if (canLeave()) load(); }
        else super.buttonPressed(id);
    }
    private String initialText() { return current.exists() ? current.source() : NameMappingsFile.INITIAL_TEXT; }
    private void load() {
        try {
            TemplateWorkspace.ensureSaved(file.path(), "settings file");
            current = file.read(); loading = true;
            try { editor.setText(initialText()); editor.setEnabled(true); }
            finally { loading = false; }
            dirty = false; updateSave(); setErrorMessage(null);
            setMessage(current.exists() ? "Edit and Save, then select Preview again in the generator."
                    : "This file does not exist yet. Save creates it; closing without saving creates no files. The example rule is commented out.");
        } catch (IOException | GenerationException e) { error(e); }
    }
    private boolean save() {
        if (current == null || current.exists() && !dirty) return true;
        try {
            TemplateWorkspace.ensureSaved(file.path(), "settings file");
            current = file.save(current, editor.getText()); dirty = false; updateSave();
            try { TemplateWorkspace.refreshFile(file.path()); }
            catch (IOException | CoreException e) { MessageDialog.openWarning(getShell(), "Type mappings", "The settings file was saved, but the workspace could not be refreshed. Refresh its folder in Explorer."); }
            setErrorMessage(null); setMessage("Saved. Close this editor and select Preview again to apply the rules.");
            return true;
        } catch (IOException | GenerationException e) { error(e); return false; }
    }
    private boolean canLeave() {
        if (!dirty) return true;
        var dialog = new MessageDialog(getShell(), "Unsaved type mapping changes", null,
                "Save changes to " + file.path().getFileName() + "?", MessageDialog.QUESTION, new String[]{"Save", "Discard", "Cancel"}, 0);
        int choice = dialog.open();
        if (choice == 0) return save();
        if (choice == 1) {
            loading = true;
            try { editor.setText(initialText()); } finally { loading = false; }
            dirty = false; updateSave(); return true;
        }
        return false;
    }
    private void updateSave() {
        if (getButton(SAVE) != null) getButton(SAVE).setEnabled(current != null && (dirty || !current.exists()));
        updatePosition();
    }
    private void updatePosition() {
        if (position == null) return;
        int offset = editor.getCaretOffset(), line = editor.getLineAtOffset(offset);
        position.setText((current != null && !current.exists() ? "New file" : "name.properties") + (dirty ? " *" : "")
                + "    Line " + (line + 1) + ", column " + (offset - editor.getOffsetAtLine(line) + 1) + "    UTF-8");
    }
    private void error(Exception error) {
        setErrorMessage(error instanceof GenerationException ? error.getMessage()
                : "Cannot read or save the settings file. Check its path, UTF-8 encoding and permissions; use Reload after external changes.");
    }
    @Override public boolean close() { return canLeave() && super.close(); }
    @Override protected Point getInitialSize() { return new Point(900, 650); }
}
