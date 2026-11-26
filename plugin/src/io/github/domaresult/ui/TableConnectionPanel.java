/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import io.github.domaresult.core.JdbcDriverLoader;
import io.github.domaresult.core.PreviewExecution;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/** Shares the existing project defaults, connection profiles and Secure Storage entries. */
final class TableConnectionPanel extends Composite {
    private final ConnectionSettingsStore projectSettings;
    private final ConnectionProfileStore store;
    private final Runnable changed;
    private final Consumer<String> message;
    private List<ConnectionProfileStore.Profile> profiles = List.of();
    private String selected;
    private final Combo profile;
    private final Text jars, driver, url, username, password;
    private final Spinner timeout;
    private final Button remember, update;
    private boolean applying, dirty, unreadable;

    TableConnectionPanel(Composite parent, IProject project, Runnable changed, Consumer<String> message) {
        super(parent, SWT.NONE);
        this.changed = changed; this.message = message;
        projectSettings = ConnectionSettingsStore.forProject(project);
        store = ConnectionProfileStore.forWorkspace(project);
        setLayout(new GridLayout(3, false));
        new Label(this, SWT.NONE).setText("Connection profile");
        Composite actions = new Composite(this, SWT.NONE);
        actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        actions.setLayout(new GridLayout(4, false));
        profile = new Combo(actions, SWT.READ_ONLY);
        profile.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        update = button(actions, "Update profile", () -> save(false));
        button(actions, "Save as...", () -> save(true));
        button(actions, "Manage...", () -> {
            new ConnectionProfilesDialog(getShell(), store).open();
            try {
                String previous = selected;
                refresh(selected);
                if (!dirty || !Objects.equals(previous, selected)) { load(); changed.run(); }
            }
            catch (IOException e) { message.accept("Cannot read connection profiles."); }
        });
        jars = field("Driver JARs", false, 1);
        button(this, "Browse...", () -> {
            FileDialog dialog = new FileDialog(getShell(), SWT.OPEN | SWT.MULTI);
            dialog.setFilterExtensions(new String[]{"*.jar"});
            if (dialog.open() != null) jars.setText(String.join(";", Arrays.stream(dialog.getFileNames())
                    .map(name -> new File(dialog.getFilterPath(), name).getAbsolutePath()).toList()));
        });
        driver = field("Driver class", false, 2);
        url = field("JDBC URL", false, 2);
        username = field("Username", false, 2);
        password = field("Password", true, 2);
        new Label(this, SWT.NONE).setText("Timeout (seconds)");
        timeout = new Spinner(this, SWT.BORDER);
        timeout.setMinimum(1); timeout.setMaximum(PreviewExecution.MAX_TIMEOUT_SECONDS);
        timeout.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        remember = new Button(this, SWT.CHECK);
        remember.setText("Save password in Eclipse Secure Storage");
        remember.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        try { refresh(projectSettings.loadSelectedProfile()); }
        catch (IOException e) { profile.setItems(new String[]{ConnectionProfileStore.UNREGISTERED}); profile.select(0); }
        load();
        for (Text input : List.of(jars, driver, url, username, password)) input.addModifyListener(e -> edited());
        for (Text input : List.of(driver, url, username)) input.addModifyListener(e -> { if (!applying) password.setText(""); });
        password.addModifyListener(e -> { if (!applying) unreadable = false; });
        timeout.addModifyListener(e -> edited());
        remember.addListener(SWT.Selection, e -> edited());
        profile.addListener(SWT.Selection, e -> {
            int index = profile.getSelectionIndex();
            String next = index <= 0 ? null : profiles.get(index - 1).id();
            if (Objects.equals(next, selected)) return;
            if (dirty && !MessageDialog.openQuestion(getShell(), "Unsaved connection edits", "Switching profiles discards these connection edits. Switch anyway?")) {
                select(); return;
            }
            selected = next; load(); changed.run();
        });
    }
    private Text field(String label, boolean secret, int span) {
        new Label(this, SWT.NONE).setText(label);
        Text text = new Text(this, SWT.BORDER | (secret ? SWT.PASSWORD : SWT.NONE));
        text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, span, 1));
        return text;
    }
    private static Button button(Composite parent, String title, Runnable action) {
        Button button = new Button(parent, SWT.PUSH); button.setText(title);
        button.addListener(SWT.Selection, e -> action.run()); return button;
    }
    private void edited() {
        if (applying) return;
        dirty = true; update.setEnabled(selected != null); changed.run();
    }
    private ConnectionSettingsStore settings() { return selected == null ? projectSettings : store.settings(selected); }
    private void refresh(String id) throws IOException {
        profiles = store.list();
        selected = profiles.stream().anyMatch(p -> p.id().equals(id)) ? id : null;
        profile.setItems(java.util.stream.Stream.concat(java.util.stream.Stream.of(ConnectionProfileStore.UNREGISTERED), profiles.stream().map(ConnectionProfileStore.Profile::name)).toArray(String[]::new));
        select();
    }
    private void select() {
        profile.select(0);
        for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).id().equals(selected)) profile.select(i + 1);
    }
    private void load() {
        applying = true;
        try {
            var settings = settings(); var saved = settings.load();
            jars.setText(saved.jars()); driver.setText(saved.driverClass()); url.setText(saved.url()); username.setText(saved.username());
            timeout.setSelection(settings.loadPreviewTimeout()); remember.setSelection(settings.remembersPassword());
            password.setText(""); unreadable = false;
            try { password.setText(settings.loadPassword(saved)); }
            catch (IOException e) { unreadable = true; message.accept("The saved password could not be read. Enter it again or check Eclipse Secure Storage."); }
        } finally { applying = false; }
        dirty = false; update.setEnabled(false);
    }
    private ConnectionSettingsStore.Connection connection() {
        return new ConnectionSettingsStore.Connection(jars.getText(), driver.getText().trim(), url.getText().trim(), username.getText());
    }
    private String savedSecret() { return unreadable && password.getText().isEmpty() ? null : password.getText(); }
    private void save(boolean saveAs) {
        try {
            if (saveAs) {
                var dialog = new InputDialog(getShell(), "Save connection profile", "Profile name (shared by projects in this workspace):", "", value -> {
                    try { return store.nameProblem(value, null); } catch (IOException e) { return "Cannot read profiles."; }
                });
                if (dialog.open() != Window.OK) return;
                selected = store.create(dialog.getValue(), connection(), timeout.getSelection(), remember.getSelection(), savedSecret()).id();
            } else store.update(selected, connection(), timeout.getSelection(), remember.getSelection(), savedSecret());
            refresh(selected); dirty = false; update.setEnabled(false);
            remember.setSelection(settings().remembersPassword());
        } catch (IOException | IllegalArgumentException e) { MessageDialog.openError(getShell(), "Connection profiles", e.getMessage()); }
    }
    void saveOnClose() {
        try {
            projectSettings.saveSelectedProfile(selected);
            if (selected == null) {
                projectSettings.savePreviewTimeout(timeout.getSelection());
                projectSettings.save(connection(), remember.getSelection(), savedSecret());
            }
        } catch (IOException e) { MessageDialog.openWarning(getShell(), "Connection settings", "Connection settings could not be saved. Check workspace permissions and Preferences > General > Security > Secure Storage."); }
        password.setText("");
    }
    Spec spec() {
        var connection = connection();
        if (connection.driverClass().isBlank() || connection.url().isBlank()) throw new IllegalArgumentException("Enter the JDBC driver class and JDBC URL.");
        return new Spec(Arrays.stream(connection.jars().split(";")).filter(p -> !p.isBlank()).map(String::trim).map(Path::of).toList(),
                connection.driverClass(), connection.url(), connection.username(), password.getText(), timeout.getSelection());
    }
    record Spec(List<Path> jars, String driver, String url, String user, String password, int timeout) {
        @Override public String toString() { return "JDBC connection (credentials omitted)"; }
        @FunctionalInterface interface Work<T> { T run(Connection connection) throws Exception; }
        <T> T run(PreviewExecution.Cancellation cancel, Work<T> work) throws Exception {
            try (JdbcDriverLoader loader = new JdbcDriverLoader(jars)) {
                cancel.driverLoader(loader);
                Thread thread = Thread.currentThread(); ClassLoader previous = thread.getContextClassLoader();
                try {
                    thread.setContextClassLoader(loader); cancel.check();
                    Connection connection = loader.connect(driver, url, user, password, timeout);
                    try (connection) { cancel.track(connection); return work.run(connection); }
                    finally { cancel.release(connection); }
                } finally { thread.setContextClassLoader(previous); }
            }
        }
    }
}
