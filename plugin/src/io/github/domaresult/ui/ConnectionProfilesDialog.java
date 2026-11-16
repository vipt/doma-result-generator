/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/** Lists profile names without unlocking Secure Storage or exposing saved credentials. */
final class ConnectionProfilesDialog extends TitleAreaDialog {
    private final ConnectionProfileStore store;
    private List<ConnectionProfileStore.Profile> profiles = List.of();
    private org.eclipse.swt.widgets.List names;
    private Button rename, delete, export;

    ConnectionProfilesDialog(Shell parent, ConnectionProfileStore store) {
        super(parent);
        this.store = store;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override protected Control createDialogArea(Composite parent) {
        setTitle("Connection profiles");
        setMessage("Shared by projects in this workspace. Select a profile in the generator to edit its connection settings.");
        Composite area = (Composite) super.createDialogArea(parent);
        Composite body = new Composite(area, SWT.NONE);
        body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        body.setLayout(new GridLayout(2, false));
        names = new org.eclipse.swt.widgets.List(body, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        names.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Composite buttons = new Composite(body, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
        buttons.setLayout(new GridLayout());
        rename = button(buttons, "Rename...", this::rename);
        delete = button(buttons, "Delete...", this::delete);
        export = button(buttons, "Export all...", this::exportProfiles);
        button(buttons, "Import...", this::importProfiles);
        Label note = new Label(body, SWT.WRAP);
        note.setText("Export includes connection URLs, usernames and local JAR paths. Passwords are excluded. Imported profiles start with password saving off; existing profiles are kept.");
        GridData noteData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        noteData.widthHint = 520;
        note.setLayoutData(noteData);
        names.addListener(SWT.Selection, e -> updateButtons());
        refresh(null);
        return area;
    }

    private Button button(Composite parent, String text, Runnable action) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(text);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        button.addListener(SWT.Selection, e -> action.run());
        return button;
    }

    private void refresh(String selected) {
        try {
            profiles = store.list();
            names.setItems(profiles.stream().map(ConnectionProfileStore.Profile::name).toArray(String[]::new));
            for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).id().equals(selected)) names.select(i);
            setErrorMessage(null);
        } catch (IOException e) { setErrorMessage(e.getMessage()); }
        updateButtons();
    }

    private void updateButtons() {
        rename.setEnabled(names.getSelectionIndex() >= 0);
        delete.setEnabled(names.getSelectionIndex() >= 0);
        export.setEnabled(!profiles.isEmpty());
    }

    private ConnectionProfileStore.Profile selected() { return profiles.get(names.getSelectionIndex()); }

    private void rename() {
        var profile = selected();
        InputDialog input = new InputDialog(getShell(), "Rename connection profile", "Connection profile name:", profile.name(), value -> {
            try { return store.nameProblem(value, profile.id()); }
            catch (IOException e) { return "Cannot read connection profiles."; }
        });
        if (input.open() != Window.OK) return;
        try { store.rename(profile.id(), input.getValue()); }
        catch (IOException | IllegalArgumentException e) { MessageDialog.openError(getShell(), "Connection profiles", e.getMessage()); }
        refresh(profile.id());
    }

    private void delete() {
        var profile = selected();
        if (!MessageDialog.openQuestion(getShell(), "Delete connection profile", "Delete '" + profile.name()
                + "' and its saved password? Projects using it will return to their unregistered settings.")) return;
        try { store.delete(profile.id()); }
        catch (IOException e) { MessageDialog.openError(getShell(), "Connection profiles", e.getMessage()); }
        refresh(null);
    }

    private void exportProfiles() {
        FileDialog file = new FileDialog(getShell(), SWT.SAVE);
        file.setText("Export connection profiles (no passwords)");
        file.setFilterExtensions(new String[] { "*.properties" });
        file.setFileName("doma-connections.properties");
        file.setOverwrite(true);
        String path = file.open();
        if (path == null) return;
        try { store.exportTo(Path.of(path)); setMessage("Connection profiles exported without passwords."); }
        catch (IOException | IllegalArgumentException e) { MessageDialog.openError(getShell(), "Connection profiles", "Could not export connection profiles. Check the file path, permissions and saved URLs."); }
    }

    private void importProfiles() {
        FileDialog file = new FileDialog(getShell(), SWT.OPEN);
        file.setText("Import connection profiles");
        file.setFilterExtensions(new String[] { "*.properties", "*" });
        String path = file.open();
        if (path == null) return;
        try {
            var imported = store.importFrom(Path.of(path));
            refresh(imported.isEmpty() ? null : imported.get(0).id());
            setMessage("Imported " + imported.size() + " connection profile(s). Re-enter passwords and check local JAR paths before use.");
        } catch (IOException | IllegalArgumentException e) {
            MessageDialog.openError(getShell(), "Connection profiles", "Could not import connection profiles. Check the file format, permissions, driver classes, URLs and timeout values.");
            refresh(null);
        }
    }

    @Override protected void createButtonsForButtonBar(Composite parent) { createButton(parent, IDialogConstants.OK_ID, "Close", true); }
    @Override protected Point getInitialSize() { return new Point(660, 440); }
}
