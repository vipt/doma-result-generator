/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/** Keeps project connection defaults in the workspace; passwords only in encrypted storage. */
final class ConnectionSettingsStore {
    private static final String PLUGIN = "io.github.domaresult.eclipse";
    private static final Pattern URL_CREDENTIALS = Pattern.compile(
            "(?i)(?:password|passwd|pwd|(?:access[_-]?)?token|secret|credential)\\s*[=:]|://[^/?#]*@|^jdbc:oracle:[^:]+:[^@]+@");
    record Connection(String jars, String driverClass, String url, String username) {}

    private final Preferences preferences;
    private final Supplier<ISecurePreferences> secureRoot;
    private final String securePath;

    static ConnectionSettingsStore forProject(IProject project) {
        String workspace = project.getWorkspace().getRoot().getLocationURI().toString();
        return new ConnectionSettingsStore(InstanceScope.INSTANCE.getNode(PLUGIN).node("connections").node(hash(project.getName())),
                SecurePreferencesFactory::getDefault, PLUGIN + "/connections/" + hash(workspace + "\0" + project.getName()));
    }

    ConnectionSettingsStore(Preferences preferences, Supplier<ISecurePreferences> secureRoot, String securePath) {
        this.preferences = preferences;
        this.secureRoot = secureRoot;
        this.securePath = securePath;
    }

    Connection load() {
        return new Connection(preferences.get("driverJars", ""), preferences.get("driverClass", ""),
                preferences.get("jdbcUrl", ""), preferences.get("username", ""));
    }

    boolean remembersPassword() { return preferences.getBoolean("rememberPassword", false); }

    String loadPackageName() { return preferences.get("packageName", "com.example.dto"); }

    void savePackageName(String value) throws IOException {
        preferences.put("packageName", value);
        flush();
    }

    String loadPassword(Connection connection) throws IOException {
        if (!remembersPassword() || !binding(connection).equals(preferences.get("passwordBinding", ""))) return "";
        try {
            ISecurePreferences root = secure();
            return root.nodeExists(securePath) ? root.node(securePath).get("password", "") : "";
        } catch (StorageException e) { throw new IOException("Cannot read the saved password.", e); }
    }

    /** A null password preserves an unreadable saved entry, only for its original connection. */
    void save(Connection connection, boolean rememberPassword, String password) throws IOException {
        boolean hadSecret = preferences.getBoolean("hasSavedPassword", false) || remembersPassword();
        boolean preserve = password == null && rememberPassword && remembersPassword()
                && binding(connection).equals(preferences.get("passwordBinding", ""));
        preferences.put("driverJars", connection.jars());
        preferences.put("driverClass", connection.driverClass());
        // Embedded credentials belong in Username/Password, never in ordinary preferences.
        preferences.put("jdbcUrl", canRememberUrl(connection.url()) ? connection.url() : "");
        preferences.put("username", connection.username());
        if (preserve) { flush(); return; }
        preferences.putBoolean("rememberPassword", false);
        preferences.remove("passwordBinding");
        flush();
        try {
            if (rememberPassword && password != null && !password.isEmpty() && canRememberUrl(connection.url())) {
                preferences.putBoolean("hasSavedPassword", true);
                flush();
                ISecurePreferences root = secure();
                root.node(securePath).put("password", password, true);
                root.flush();
                preferences.put("passwordBinding", binding(connection));
                preferences.putBoolean("rememberPassword", true);
                flush();
            } else if (hadSecret) {
                ISecurePreferences root = secure();
                if (root.nodeExists(securePath)) {
                    root.node(securePath).removeNode();
                    root.flush();
                }
                preferences.remove("hasSavedPassword");
                flush();
            }
        } catch (StorageException e) { throw new IOException("Cannot save or remove the password.", e); }
    }

    static boolean canRememberUrl(String url) {
        try { return !URL_CREDENTIALS.matcher(URLDecoder.decode(url, StandardCharsets.UTF_8)).find(); }
        catch (IllegalArgumentException e) { return false; }
    }

    private ISecurePreferences secure() throws IOException {
        ISecurePreferences root = secureRoot.get();
        if (root == null) throw new IOException("Eclipse Secure Storage is unavailable.");
        return root;
    }

    private void flush() throws IOException {
        try { preferences.flush(); }
        catch (BackingStoreException e) { throw new IOException("Cannot save connection defaults.", e); }
    }

    private static String binding(Connection connection) {
        return hash(connection.driverClass() + "\0" + connection.url() + "\0" + connection.username());
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
