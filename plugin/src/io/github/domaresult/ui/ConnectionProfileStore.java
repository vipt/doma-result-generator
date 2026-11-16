/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import io.github.domaresult.core.PreviewExecution;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/** Named connections shared by projects in one workspace, with independent encrypted secrets. */
final class ConnectionProfileStore {
    static final String UNREGISTERED = "Unregistered (project settings)";
    record Profile(String id, String name) {}
    private record Imported(String name, ConnectionSettingsStore.Connection connection, int timeout) {}
    private final Preferences preferences;
    private final Supplier<ISecurePreferences> secureRoot;
    private final String securePath;

    static ConnectionProfileStore forWorkspace(IProject project) {
        String workspace = project.getWorkspace().getRoot().getLocationURI().toString();
        return new ConnectionProfileStore(InstanceScope.INSTANCE.getNode(ConnectionSettingsStore.PLUGIN).node("profiles"),
                SecurePreferencesFactory::getDefault, ConnectionSettingsStore.PLUGIN + "/profiles/" + ConnectionSettingsStore.hash(workspace));
    }

    ConnectionProfileStore(Preferences preferences, Supplier<ISecurePreferences> secureRoot, String securePath) {
        this.preferences = preferences;
        this.secureRoot = secureRoot;
        this.securePath = securePath;
    }

    List<Profile> list() throws IOException {
        try {
            List<Profile> result = new ArrayList<>();
            for (String id : preferences.childrenNames()) {
                if (!validId(id)) continue;
                String name = preferences.node(id).get("name", "");
                if (!name.isBlank()) result.add(new Profile(id, name));
            }
            result.sort(Comparator.comparing(Profile::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Profile::id));
            return List.copyOf(result);
        } catch (BackingStoreException e) { throw new IOException("Cannot read connection profiles.", e); }
    }

    String nameProblem(String name, String exceptId) throws IOException {
        String value = name.trim();
        if (value.isEmpty()) return "Enter a connection profile name.";
        if (value.equalsIgnoreCase(UNREGISTERED)) return "Choose a different name; this label is reserved for project settings.";
        if (value.length() > 100 || value.chars().anyMatch(Character::isISOControl)) return "Use a name of at most 100 characters without control characters.";
        for (Profile profile : list()) {
            if (!profile.id().equals(exceptId) && profile.name().equalsIgnoreCase(value)) return "A profile with that name already exists.";
        }
        return null;
    }

    Profile create(String name, ConnectionSettingsStore.Connection connection, int timeout, boolean rememberPassword, String password) throws IOException {
        validateName(name, null);
        validate(connection, timeout);
        if (rememberPassword && password == null) throw new IOException("The saved password could not be read. Re-enter it or clear Save password before saving a new profile.");
        String id = UUID.randomUUID().toString();
        Preferences node = preferences.node(id);
        ConnectionSettingsStore settings = settings(id);
        try {
            settings.savePreviewTimeout(timeout);
            settings.save(connection, rememberPassword, password);
            node.put("name", name.trim());
            flush();
            return new Profile(id, name.trim());
        } catch (IOException failure) {
            // A failed registration must not appear as a complete profile or fall back to plaintext.
            try { settings.save(connection, false, ""); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            try { node.removeNode(); flush(); }
            catch (BackingStoreException | IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    void update(String id, ConnectionSettingsStore.Connection connection, int timeout, boolean rememberPassword, String password) throws IOException {
        require(id);
        validate(connection, timeout);
        ConnectionSettingsStore settings = settings(id);
        if (rememberPassword && password == null && !settings.canPreservePassword(connection)) {
            throw new IOException("Re-enter the password or clear Save password before updating this connection.");
        }
        settings.savePreviewTimeout(timeout);
        settings.save(connection, rememberPassword, password);
    }

    void rename(String id, String name) throws IOException {
        require(id);
        validateName(name, id);
        preferences.node(id).put("name", name.trim());
        flush();
    }

    void delete(String id) throws IOException {
        require(id);
        ConnectionSettingsStore settings = settings(id);
        // Keep the node available for retry if Secure Storage refuses the deletion.
        settings.save(settings.load(), false, "");
        try { preferences.node(id).removeNode(); flush(); }
        catch (BackingStoreException e) { throw new IOException("Cannot delete the connection profile.", e); }
    }

    ConnectionSettingsStore settings(String id) {
        if (!validId(id)) throw new IllegalArgumentException("Invalid connection profile identifier.");
        return new ConnectionSettingsStore(preferences.node(id), secureRoot, securePath + "/" + id);
    }

    void exportTo(Path file) throws IOException {
        List<Profile> profiles = list();
        Properties data = new Properties();
        data.setProperty("format", "doma-result-generator-connections-1");
        data.setProperty("count", Integer.toString(profiles.size()));
        for (int i = 0; i < profiles.size(); i++) {
            Profile profile = profiles.get(i);
            ConnectionSettingsStore settings = settings(profile.id());
            var connection = settings.load();
            validate(connection, settings.loadPreviewTimeout());
            String prefix = "profile." + i + ".";
            data.setProperty(prefix + "name", profile.name());
            data.setProperty(prefix + "driverJars", connection.jars());
            data.setProperty(prefix + "driverClass", connection.driverClass());
            data.setProperty(prefix + "jdbcUrl", connection.url());
            data.setProperty(prefix + "username", connection.username());
            data.setProperty(prefix + "timeoutSeconds", Integer.toString(settings.loadPreviewTimeout()));
        }
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            data.store(writer, "Doma Result Generator connection profiles - no passwords");
        }
    }

    List<Profile> importFrom(Path file) throws IOException {
        if (Files.size(file) > 2 * 1024 * 1024) throw new IOException("Connection profiles file is too large (maximum 2 MiB).");
        Properties data = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { data.load(reader); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid connection profiles file."); }
        List<Imported> entries = new ArrayList<>();
        try {
            if (!"doma-result-generator-connections-1".equals(data.getProperty("format"))) throw new IllegalArgumentException();
            int count = Integer.parseInt(data.getProperty("count", "-1"));
            if (count < 0 || count > 1000) throw new IllegalArgumentException();
            for (int i = 0; i < count; i++) {
                String prefix = "profile." + i + ".";
                String name = data.getProperty(prefix + "name", "").trim();
                if (name.isEmpty() || name.length() > 100 || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
                var connection = new ConnectionSettingsStore.Connection(data.getProperty(prefix + "driverJars", ""),
                        data.getProperty(prefix + "driverClass", ""), data.getProperty(prefix + "jdbcUrl", ""), data.getProperty(prefix + "username", ""));
                int timeout = Integer.parseInt(data.getProperty(prefix + "timeoutSeconds", "30"));
                validate(connection, timeout);
                entries.add(new Imported(name, connection, timeout));
            }
        } catch (IllegalArgumentException e) { throw new IOException("Invalid connection profiles file. Check its format, driver class, URL and timeout; embedded credentials are not supported."); }
        List<Profile> imported = new ArrayList<>();
        for (Imported entry : entries) {
            String name = entry.name();
            for (int suffix = 1; nameProblem(name, null) != null; suffix++) {
                String ending = " (imported " + suffix + ")";
                name = entry.name().substring(0, Math.min(entry.name().length(), 100 - ending.length())) + ending;
            }
            imported.add(create(name, entry.connection(), entry.timeout(), false, ""));
        }
        return List.copyOf(imported);
    }

    private void validateName(String name, String exceptId) throws IOException {
        String problem = nameProblem(name, exceptId);
        if (problem != null) throw new IllegalArgumentException(problem);
    }

    private static void validate(ConnectionSettingsStore.Connection connection, int timeout) {
        if (List.of(connection.jars(), connection.driverClass(), connection.url(), connection.username()).stream().anyMatch(value -> value.length() > 8192)) {
            throw new IllegalArgumentException("A connection setting is too long (maximum 8192 characters).");
        }
        if (connection.driverClass().isBlank() || connection.url().isBlank()) throw new IllegalArgumentException("Enter the driver class and JDBC URL before saving a profile.");
        if (!ConnectionSettingsStore.canRememberUrl(connection.url())) throw new IllegalArgumentException("Enter credentials in Username and Password before saving a profile; URLs containing credentials cannot be saved.");
        if (timeout < 1 || timeout > PreviewExecution.MAX_TIMEOUT_SECONDS) throw new IllegalArgumentException("Preview timeout must be between 1 and 600 seconds.");
    }

    private void require(String id) throws IOException {
        if (list().stream().noneMatch(profile -> profile.id().equals(id))) throw new IOException("The connection profile was deleted. Select another profile or save a new one.");
    }

    private static boolean validId(String id) { return id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); }

    private void flush() throws IOException {
        try { preferences.flush(); }
        catch (BackingStoreException e) { throw new IOException("Cannot save connection profiles. Check workspace permissions.", e); }
    }
}
