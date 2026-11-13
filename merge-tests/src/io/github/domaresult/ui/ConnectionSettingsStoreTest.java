/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.PBEKeySpec;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.provider.IProviderHints;
import org.junit.*;
import org.osgi.service.prefs.Preferences;

/** Uses real workspace preferences and a separate, encrypted test keyring without UI prompts. */
public class ConnectionSettingsStoreTest {
    private Preferences preferences;
    private ISecurePreferences secure;
    private Path keyring, directory;
    private ConnectionSettingsStore store;
    private final ConnectionSettingsStore.Connection connection = new ConnectionSettingsStore.Connection(
            "/drivers/jdbc.jar", "oracle.jdbc.OracleDriver", "jdbc:oracle:thin:@localhost:1521/example", "test_user");
    private static final String PASSWORD = "test-only-password-\u65e5\u672c\u8a9e";

    @Before public void setUp() throws Exception {
        preferences = InstanceScope.INSTANCE.getNode("io.github.domaresult.settings.tests").node(UUID.randomUUID().toString());
        directory = Files.createTempDirectory("doma-settings-test-");
        keyring = directory.resolve("secure_storage");
        secure = SecurePreferencesFactory.open(keyring.toUri().toURL(), Map.of(
                IProviderHints.DEFAULT_PASSWORD, new PBEKeySpec("test-only-master-password".toCharArray()),
                IProviderHints.PROMPT_USER, false));
        store = new ConnectionSettingsStore(preferences, () -> secure, "test/workspace/project");
    }

    @After public void tearDown() throws Exception {
        if (preferences != null) {
            var parent = preferences.parent();
            preferences.removeNode();
            parent.flush();
        }
        if (secure != null) { secure.clear(); secure.flush(); }
        if (directory != null) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    @Test public void remembersConnectionDefaultsAcrossInstancesWithoutSavingPasswordByDefault() throws Exception {
        assertFalse(store.remembersPassword());
        store.save(connection, false, PASSWORD);
        var reopened = new ConnectionSettingsStore(preferences, () -> { throw new AssertionError("No keyring access needed"); }, "test/workspace/project");
        assertEquals(connection, reopened.load());
        assertEquals("", reopened.loadPassword(reopened.load()));
        assertFalse(secure.nodeExists("test/workspace/project"));
        for (String key : preferences.keys()) assertFalse(preferences.get(key, "").contains(PASSWORD));
    }

    @Test public void optInStoresEncryptedPasswordAndRestoresItForTheSameConnection() throws Exception {
        store.save(connection, true, PASSWORD);
        assertTrue(store.remembersPassword());
        assertTrue(secure.node("test/workspace/project").isEncrypted("password"));
        var reopened = new ConnectionSettingsStore(preferences, () -> secure, "test/workspace/project");
        assertEquals(PASSWORD, reopened.loadPassword(reopened.load()));
        assertFalse(Files.readString(keyring).contains(PASSWORD));
        assertNull(preferences.get("password", null));
    }

    @Test public void optingOutRemovesThePreviouslyEncryptedPassword() throws Exception {
        store.save(connection, true, PASSWORD);
        store.save(connection, false, PASSWORD);
        assertFalse(store.remembersPassword());
        assertEquals("", store.loadPassword(connection));
        assertFalse(secure.nodeExists("test/workspace/project"));
        assertEquals(connection, store.load());
    }

    @Test public void clearingThePasswordRemovesItsSavedValue() throws Exception {
        store.save(connection, true, PASSWORD);
        store.save(connection, true, "");
        assertFalse(store.remembersPassword());
        assertFalse(secure.nodeExists("test/workspace/project"));
    }

    @Test public void passwordIsNotRestoredForAnotherUrlUserOrDriver() throws Exception {
        store.save(connection, true, PASSWORD);
        assertEquals("", store.loadPassword(new ConnectionSettingsStore.Connection(connection.jars(), connection.driverClass(), "jdbc:h2:mem:other", connection.username())));
        assertEquals("", store.loadPassword(new ConnectionSettingsStore.Connection(connection.jars(), connection.driverClass(), connection.url(), "other_user")));
        assertEquals("", store.loadPassword(new ConnectionSettingsStore.Connection(connection.jars(), "other.Driver", connection.url(), connection.username())));
        assertEquals(PASSWORD, store.loadPassword(connection));
    }

    @Test public void projectsAndWorkspacesHaveIndependentPasswordEntries() throws Exception {
        store.save(connection, true, PASSWORD);
        Preferences other = preferences.node("other");
        var otherStore = new ConnectionSettingsStore(other, () -> secure, "test/another-workspace/project");
        otherStore.save(connection, true, "other-password");
        assertEquals(PASSWORD, store.loadPassword(connection));
        assertEquals("other-password", otherStore.loadPassword(connection));
        otherStore.save(connection, false, "");
        assertEquals(PASSWORD, store.loadPassword(connection));
    }

    @Test public void failedEncryptionKeepsDefaultsButNeverFallsBackToPlaintext() throws Exception {
        var unavailable = new ConnectionSettingsStore(preferences, () -> null, "test/workspace/project");
        assertThrows(IOException.class, () -> unavailable.save(connection, true, PASSWORD));
        assertEquals(connection, unavailable.load());
        assertFalse(unavailable.remembersPassword());
        for (String key : preferences.keys()) assertFalse(preferences.get(key, "").contains(PASSWORD));
    }

    @Test public void failedDeletionDisablesRestorationAndCanBeRetried() throws Exception {
        store.save(connection, true, PASSWORD);
        var unavailable = new ConnectionSettingsStore(preferences, () -> null, "test/workspace/project");
        assertThrows(IOException.class, () -> unavailable.save(connection, false, ""));
        assertFalse(unavailable.remembersPassword());
        store.save(connection, false, "");
        assertFalse(secure.nodeExists("test/workspace/project"));
    }

    @Test public void unreadableSavedPasswordCanBeKeptWithoutRewritingOrUnlockingIt() throws Exception {
        store.save(connection, true, PASSWORD);
        var unavailable = new ConnectionSettingsStore(preferences, () -> { throw new AssertionError("Do not unlock"); }, "test/workspace/project");
        unavailable.save(connection, true, null);
        assertEquals(PASSWORD, store.loadPassword(connection));
    }

    @Test public void ordinaryPreferencesDoNotStoreUrlsWithEmbeddedCredentials() throws Exception {
        for (String url : new String[] {
                "jdbc:oracle:thin:user/secret@localhost:1521/example",
                "jdbc:postgresql://localhost/example?password=secret",
                "jdbc:postgresql://user:secret@localhost/example",
                "jdbc:sqlserver://localhost;PWD={secret}",
                "jdbc:mysql://localhost/example?%70assword=secret",
                "jdbc:h2:mem:test;ACCESS_TOKEN=secret"
        }) {
            store.save(new ConnectionSettingsStore.Connection(connection.jars(), connection.driverClass(), url, "test_user"), true, PASSWORD);
            assertEquals("", store.load().url());
            assertFalse(store.remembersPassword());
            for (String key : preferences.keys()) assertFalse(preferences.get(key, "").contains("secret"));
        }
    }
}
