/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.crypto.spec.PBEKeySpec;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.provider.IProviderHints;
import org.junit.*;
import org.osgi.service.prefs.Preferences;

/** Exercises shared profiles against real workspace preferences and an isolated encrypted keyring. */
public class ConnectionProfileStoreTest {
    private Preferences preferences;
    private ISecurePreferences secure;
    private Path directory, keyring;
    private ConnectionProfileStore profiles;
    private final ConnectionSettingsStore.Connection connection = new ConnectionSettingsStore.Connection(
            "/drivers/jdbc.jar", "oracle.jdbc.OracleDriver", "jdbc:oracle:thin:@localhost:1521/example", "test_user");
    private static final String PASSWORD = "test-only-profile-password-\u65e5\u672c\u8a9e";

    @Before public void setUp() throws Exception {
        preferences = InstanceScope.INSTANCE.getNode("io.github.domaresult.profile.tests").node(UUID.randomUUID().toString());
        directory = Files.createTempDirectory("doma-profiles-test-");
        keyring = directory.resolve("secure_storage");
        secure = SecurePreferencesFactory.open(keyring.toUri().toURL(), Map.of(
                IProviderHints.DEFAULT_PASSWORD, new PBEKeySpec("test-only-master-password".toCharArray()),
                IProviderHints.PROMPT_USER, false));
        profiles = new ConnectionProfileStore(preferences.node("profiles"), () -> secure, "test/workspace/profiles");
    }

    @After public void tearDown() throws Exception {
        if (preferences != null) { var parent = preferences.parent(); preferences.removeNode(); parent.flush(); }
        if (secure != null) { secure.clear(); secure.flush(); }
        if (directory != null) try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test public void profilesAreSharedAcrossProjectsAndSelectionAndPackagesRemainPerProject() throws Exception {
        var firstProject = project("first");
        var secondProject = project("second");
        var dev = profiles.create("Development", connection, 45, false, "");
        var qa = profiles.create("QA", connection, 75, false, "");
        firstProject.saveSelectedProfile(dev.id());
        secondProject.saveSelectedProfile(qa.id());
        firstProject.savePackageName("first.results");
        secondProject.savePackageName("second.results");
        var reopened = reopened();
        assertEquals(2, reopened.list().size());
        assertEquals(connection, reopened.settings(project("first").loadSelectedProfile()).load());
        assertEquals(45, reopened.settings(firstProject.loadSelectedProfile()).loadPreviewTimeout());
        assertEquals(75, reopened.settings(secondProject.loadSelectedProfile()).loadPreviewTimeout());
        secondProject.saveSelectedProfile(dev.id());
        var updated = new ConnectionSettingsStore.Connection(connection.jars(), connection.driverClass(), "jdbc:oracle:thin:@localhost:1521/updated", "updated_user");
        profiles.update(dev.id(), updated, 90, false, "");
        assertEquals(updated, reopened.settings(secondProject.loadSelectedProfile()).load());
        assertEquals(90, reopened.settings(firstProject.loadSelectedProfile()).loadPreviewTimeout());
        assertEquals("first.results", firstProject.loadPackageName());
        assertEquals("second.results", secondProject.loadPackageName());
        firstProject.saveSelectedProfile(null);
        assertEquals("", firstProject.loadSelectedProfile());
        assertEquals(dev.id(), secondProject.loadSelectedProfile());
    }

    @Test public void existingSettingsAndEncryptedPasswordCanBeRegisteredWithoutReentry() throws Exception {
        var legacy = project("legacy");
        legacy.save(connection, true, PASSWORD);
        legacy.savePreviewTimeout(48);
        legacy.savePackageName("legacy.results");
        var profile = profiles.create("既存の開発DB", legacy.load(), legacy.loadPreviewTimeout(), legacy.remembersPassword(), legacy.loadPassword(legacy.load()));
        legacy.saveSelectedProfile(profile.id());
        var saved = reopened().settings(profile.id());
        assertEquals(connection, saved.load());
        assertEquals(48, saved.loadPreviewTimeout());
        assertEquals(PASSWORD, saved.loadPassword(saved.load()));
        assertTrue(secure.node("test/workspace/profiles/" + profile.id()).isEncrypted("password"));
        assertEquals(PASSWORD, legacy.loadPassword(legacy.load()));
        assertEquals("legacy.results", legacy.loadPackageName());
        assertFalse(Files.readString(keyring).contains(PASSWORD));
        assertNoPlaintext(preferences);
    }

    @Test public void browsingRenamingAndExportingDoNotUnlockSecureStorage() throws Exception {
        var profile = profiles.create("Development", connection, 30, true, PASSWORD);
        var locked = new ConnectionProfileStore(preferences.node("profiles"), () -> { throw new AssertionError("Must not unlock"); }, "test/workspace/profiles");
        assertEquals(1, locked.list().size());
        locked.rename(profile.id(), "Renamed");
        locked.exportTo(directory.resolve("export.properties"));
        assertEquals(profile.id(), locked.list().get(0).id());
        assertEquals("Renamed", profiles.list().get(0).name());
        assertEquals(PASSWORD, profiles.settings(profile.id()).loadPassword(connection));
    }

    @Test public void namesAreTrimmedUniqueAndValidatedBeforeWriting() throws Exception {
        var profile = profiles.create("  Development  ", connection, 30, false, "");
        assertEquals("Development", profile.name());
        for (String name : new String[] { "", " ", "development", "bad\nname", "x".repeat(101), ConnectionProfileStore.UNREGISTERED }) {
            assertThrows(IllegalArgumentException.class, () -> profiles.create(name, connection, 30, false, ""));
        }
        assertEquals(1, profiles.list().size());
        profiles.rename(profile.id(), "DEVELOPMENT");
        assertEquals("DEVELOPMENT", profiles.list().get(0).name());
    }

    @Test public void newAndClonedProfilesHaveIndependentPasswordsAndOptOutDeletesOnlyTheirOwnSecret() throws Exception {
        var first = profiles.create("First", connection, 30, true, PASSWORD);
        var second = profiles.create("Second", connection, 30, true, "second-password");
        var unsaved = profiles.create("Without password", connection, 30, false, PASSWORD);
        assertEquals("", profiles.settings(unsaved.id()).loadPassword(connection));
        profiles.update(second.id(), connection, 50, false, "");
        assertFalse(secure.nodeExists("test/workspace/profiles/" + second.id()));
        assertEquals(PASSWORD, profiles.settings(first.id()).loadPassword(connection));
        assertEquals(50, profiles.settings(second.id()).loadPreviewTimeout());
        assertNoPlaintext(preferences);
    }

    @Test public void connectionChangesCannotReuseAnUnreadablePasswordForAnotherIdentity() throws Exception {
        var profile = profiles.create("Development", connection, 30, true, PASSWORD);
        var other = new ConnectionSettingsStore.Connection(connection.jars(), connection.driverClass(), connection.url(), "other_user");
        assertThrows(IOException.class, () -> profiles.update(profile.id(), other, 80, true, null));
        assertEquals(connection, profiles.settings(profile.id()).load());
        assertEquals(30, profiles.settings(profile.id()).loadPreviewTimeout());
        profiles.update(profile.id(), other, 80, true, "other-password");
        assertEquals("", profiles.settings(profile.id()).loadPassword(connection));
        assertEquals("other-password", profiles.settings(profile.id()).loadPassword(other));
    }

    @Test public void unreadableExistingSecretCanBePreservedOnUpdateButNotCloned() throws Exception {
        var profile = profiles.create("Development", connection, 30, true, PASSWORD);
        var locked = new ConnectionProfileStore(preferences.node("profiles"), () -> { throw new AssertionError("Do not unlock"); }, "test/workspace/profiles");
        locked.update(profile.id(), connection, 65, true, null);
        assertEquals(PASSWORD, profiles.settings(profile.id()).loadPassword(connection));
        assertEquals(65, profiles.settings(profile.id()).loadPreviewTimeout());
        assertThrows(IOException.class, () -> locked.create("Clone", connection, 30, true, null));
        assertEquals(1, profiles.list().size());
    }

    @Test public void failedPasswordRegistrationLeavesNoVisibleProfileOrPlaintextPassword() throws Exception {
        var unavailable = new ConnectionProfileStore(preferences.node("profiles"), () -> null, "test/workspace/profiles");
        assertThrows(IOException.class, () -> unavailable.create("Development", connection, 30, true, PASSWORD));
        assertTrue(profiles.list().isEmpty());
        assertNoPlaintext(preferences);
    }

    @Test public void deletionCanBeRetriedAfterSecureStorageFailureAndDoesNotDeleteOtherProfiles() throws Exception {
        var first = profiles.create("First", connection, 30, true, PASSWORD);
        var second = profiles.create("Second", connection, 30, true, "second-password");
        var unavailable = new ConnectionProfileStore(preferences.node("profiles"), () -> null, "test/workspace/profiles");
        assertThrows(IOException.class, () -> unavailable.delete(first.id()));
        assertEquals(2, profiles.list().size());
        assertFalse(profiles.settings(first.id()).remembersPassword());
        profiles.delete(first.id());
        assertEquals(java.util.List.of(second), profiles.list());
        assertFalse(secure.nodeExists("test/workspace/profiles/" + first.id()));
        assertEquals("second-password", profiles.settings(second.id()).loadPassword(connection));
        assertThrows(IOException.class, () -> profiles.update(first.id(), connection, 30, false, ""));
    }

    @Test public void invalidConnectionsAndTimeoutsAreRejectedWithoutCreatingProfiles() throws Exception {
        for (String url : new String[] { "", "jdbc:oracle:thin:user/secret@localhost:1521/test", "jdbc:mysql://localhost/test?%70assword=secret" }) {
            var invalid = new ConnectionSettingsStore.Connection(connection.jars(), connection.driverClass(), url, connection.username());
            assertThrows(IllegalArgumentException.class, () -> profiles.create("Invalid", invalid, 30, true, PASSWORD));
        }
        for (int timeout : new int[] { 0, 601 }) assertThrows(IllegalArgumentException.class, () -> profiles.create("Invalid", connection, timeout, false, ""));
        var tooLong = new ConnectionSettingsStore.Connection("x".repeat(8193), connection.driverClass(), connection.url(), connection.username());
        assertThrows(IllegalArgumentException.class, () -> profiles.create("Invalid", tooLong, 30, false, ""));
        assertTrue(profiles.list().isEmpty());
    }

    @Test public void profileSecretsAreIsolatedAcrossWorkspaces() throws Exception {
        var first = profiles.create("Development", connection, 30, true, PASSWORD);
        var other = new ConnectionProfileStore(preferences.node("other-workspace"), () -> secure, "test/other-workspace/profiles");
        assertTrue(other.list().isEmpty());
        var second = other.create("Development", connection, 30, true, "other-password");
        other.delete(second.id());
        assertEquals(PASSWORD, profiles.settings(first.id()).loadPassword(connection));
    }

    @Test public void exportAndImportRoundTripUnicodeSettingsWithFreshIdsAndWithoutPasswordsOrPackageSettings() throws Exception {
        var unicode = new ConnectionSettingsStore.Connection("/日本語/jdbc.jar", connection.driverClass(), connection.url(), "利用者");
        var original = profiles.create("開発DB", unicode, 61, true, PASSWORD);
        Path file = directory.resolve("connections.properties");
        profiles.exportTo(file);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(text.contains(PASSWORD));
        Properties exported = new Properties();
        try (var reader = Files.newBufferedReader(file)) { exported.load(reader); }
        assertFalse(exported.keySet().stream().anyMatch(key -> key.toString().matches("(?i).*(password|package|secure|binding).*")));
        var other = new ConnectionProfileStore(preferences.node("other-workspace"), () -> { throw new AssertionError("Import must not unlock"); }, "test/other-workspace/profiles");
        var imported = other.importFrom(file).get(0);
        assertNotEquals(original.id(), imported.id());
        assertEquals("開発DB", imported.name());
        assertEquals(unicode, other.settings(imported.id()).load());
        assertEquals(61, other.settings(imported.id()).loadPreviewTimeout());
        assertFalse(other.settings(imported.id()).remembersPassword());
        assertEquals("", other.settings(imported.id()).loadPassword(unicode));
        assertEquals(PASSWORD, profiles.settings(original.id()).loadPassword(unicode));
    }

    @Test public void importingNameCollisionsAddsProfilesWithoutOverwritingExistingSecrets() throws Exception {
        var original = profiles.create("Development", connection, 30, true, PASSWORD);
        Path file = directory.resolve("connections.properties");
        profiles.exportTo(file);
        var firstImport = profiles.importFrom(file).get(0);
        assertEquals("Development (imported 1)", firstImport.name());
        profiles.importFrom(file);
        assertEquals(3, profiles.list().size());
        assertEquals(PASSWORD, profiles.settings(original.id()).loadPassword(connection));
    }

    @Test public void malformedImportsAreFullyValidatedBeforeAnyProfileIsAdded() throws Exception {
        profiles.create("Development", connection, 30, false, "");
        Path file = directory.resolve("connections.properties");
        profiles.exportTo(file);
        Properties data = new Properties();
        try (var reader = Files.newBufferedReader(file)) { data.load(reader); }
        data.setProperty("count", "2");
        data.setProperty("profile.1.name", "Invalid second entry");
        data.setProperty("profile.1.driverClass", connection.driverClass());
        data.setProperty("profile.1.jdbcUrl", "jdbc:mysql://localhost/test?password=secret");
        try (var writer = Files.newBufferedWriter(file)) { data.store(writer, "test only"); }
        assertThrows(IOException.class, () -> profiles.importFrom(file));
        assertEquals(1, profiles.list().size());
        Files.writeString(file, "format=unknown\ncount=1\n");
        assertThrows(IOException.class, () -> profiles.importFrom(file));
        Files.writeString(file, "format=\\uZZZZ");
        assertThrows(IOException.class, () -> profiles.importFrom(file));
        assertEquals(1, profiles.list().size());
    }

    @Test public void emptyOrInvalidProfileIdsCannotCreatePreferenceSubtrees() throws Exception {
        for (String id : new String[] { "", "../project", "a/b", "not-a-uuid" }) {
            assertThrows(IllegalArgumentException.class, () -> profiles.settings(id));
            assertThrows(IOException.class, () -> profiles.delete(id));
        }
        assertEquals(0, preferences.node("profiles").childrenNames().length);
    }

    private ConnectionSettingsStore project(String name) {
        return new ConnectionSettingsStore(preferences.node("projects").node(name), () -> secure, "test/workspace/projects/" + name);
    }

    private ConnectionProfileStore reopened() { return new ConnectionProfileStore(preferences.node("profiles"), () -> secure, "test/workspace/profiles"); }

    private void assertNoPlaintext(Preferences node) throws Exception {
        for (String key : node.keys()) assertFalse(node.get(key, "").contains(PASSWORD));
        for (String child : node.childrenNames()) assertNoPlaintext(node.node(child));
    }
}
