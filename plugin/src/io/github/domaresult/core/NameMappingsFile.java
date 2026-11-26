/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.io.IOException;
import java.nio.file.Path;

/** Plain-text name.properties edits retain order, comments, regular expressions and formatting. */
public final class NameMappingsFile {
    public static final String INITIAL_TEXT = """
            # UTF-8: property-name-regex=fully.qualified.JavaType
            # Rules match generated Java property names; the first match wins.
            # Example (remove the leading # to enable):
            # .*Datetime$=java.time.LocalDateTime
            """;
    private final Utf8SettingsFile file;
    public NameMappingsFile(Path path) { file = new Utf8SettingsFile(path); }
    public Path path() { return file.path(); }
    public Utf8SettingsFile.Snapshot read() throws IOException { return file.read(); }
    public Utf8SettingsFile.Snapshot save(Utf8SettingsFile.Snapshot before, String source) throws IOException {
        // Reuse existing mapping validation; never reserialize through java.util.Properties.
        TypeNameMappings.parse(source);
        return file.save(before, source);
    }
}
