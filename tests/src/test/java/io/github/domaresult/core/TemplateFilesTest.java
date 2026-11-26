/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateFilesTest {
    @TempDir Path temp;
    @Test void copiesEffectiveDefaultsIncludingEmptySharedTemplateWithoutChangingCustomFiles() throws Exception {
        Path directory = temp.resolve("templates");
        var files = new TemplateFiles(directory);
        assertTrue(files.list().isEmpty()); assertFalse(Files.exists(directory));
        assertEquals(6, files.copyMissingDefaults().size());
        assertEquals(new TreeSet<>(DomaGenTemplates.NAMES), new TreeSet<>(files.list()));
        for (String name : files.list()) DomaGenTemplates.validate(name, files.read(name).source());
        assertTrue(files.read("entity.ftl").source().contains("private ${property.propertyClassSimpleName}"));
        assertEquals("", files.read("lib.ftl").source());
        var before = files.read("dao.ftl"); files.save(before, "// カスタム\n" + before.source());
        var custom = files.read("dao.ftl");
        assertTrue(files.copyMissingDefaults().isEmpty()); assertEquals(custom, files.read("dao.ftl"));
    }
    @Test void existingTemplatesArePreservedWhenAddingMissingDefaults() throws Exception {
        Files.writeString(temp.resolve("dao.ftl"), "custom DAO");
        Files.writeString(temp.resolve("lib.ftl"), "");
        var files = new TemplateFiles(temp);
        assertEquals(4, files.copyMissingDefaults().size());
        assertEquals("custom DAO", files.read("dao.ftl").source()); assertEquals("", files.read("lib.ftl").source());
    }
    @Test void editsAreUtf8AndPreserveBomAndLineEndings() throws Exception {
        var files = new TemplateFiles(temp); files.create("custom.ftl");
        String text = "\uFEFF<#-- 説明 -->\r\n${simpleName}\r\n";
        var saved = files.save(files.read("custom.ftl"), text);
        assertEquals(text, saved.source()); assertEquals(text, Files.readString(temp.resolve("custom.ftl")));
        try (var paths = Files.list(temp)) { assertEquals(List.of("custom.ftl"), paths.map(p -> p.getFileName().toString()).toList()); }
    }
    @Test void malformedSyntaxDoesNotReplaceTheSavedTemplate() throws Exception {
        var files = new TemplateFiles(temp); files.create("entity.ftl");
        var before = files.read("entity.ftl");
        var error = assertThrows(GenerationException.class, () -> files.save(before, "<#if true>unclosed\n"));
        assertTrue(error.getMessage().contains("line")); assertEquals(before, files.read("entity.ftl"));
    }
    @Test void staleEditsCannotOverwriteExternallyChangedContentEvenWithSameTimestamp() throws Exception {
        var files = new TemplateFiles(temp); files.create("dao.ftl");
        var before = files.read("dao.ftl");
        Files.writeString(temp.resolve("dao.ftl"), "// outside edit\n"); Files.setLastModifiedTime(temp.resolve("dao.ftl"), before.modified());
        assertThrows(GenerationException.class, () -> files.save(before, "// editor change\n"));
        assertEquals("// outside edit\n", files.read("dao.ftl").source());
    }
    @Test void pathsCannotEscapeTheSelectedDirectoryAndNewFilesCannotReplaceExistingOnes() throws Exception {
        var files = new TemplateFiles(temp);
        for (String name : List.of("../outside.ftl", "/outside.ftl", "a/../../outside.ftl", "a\\..\\outside.ftl", "a\n.ftl", "not-java.txt", "")) {
            assertThrows(IllegalArgumentException.class, () -> files.create(name), name);
        }
        files.create("custom.sql.ftl");
        assertThrows(FileAlreadyExistsException.class, () -> files.create("custom.sql.ftl"));
    }
    @Test void nestedTemplatesAreListedAndFileSymlinksAreNotEdited() throws Exception {
        var files = new TemplateFiles(temp.resolve("templates")); files.create("lib/common.ftl");
        Files.writeString(files.directory().resolve("readme.txt"), "not a template");
        Path outside = temp.resolve("outside.ftl"); Files.writeString(outside, "original");
        Files.createSymbolicLink(files.directory().resolve("linked.ftl"), outside);
        assertEquals(List.of("lib/common.ftl"), files.list());
        assertThrows(GenerationException.class, () -> files.read("linked.ftl")); assertEquals("original", Files.readString(outside));
    }
}
