/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.seasar.doma.extension.gen.EntityDesc;
import org.seasar.doma.extension.gen.EntityPropertyClassNameResolver;

class NameMappingsFileTest {
    @TempDir Path temp;
    @Test void openingNewSettingsDoesNotCreateDirectoriesOrEnableTheCommentedExample() throws Exception {
        var file = new NameMappingsFile(temp.resolve("config/name.properties"));
        assertFalse(file.read().exists()); assertFalse(Files.exists(file.path().getParent()));
        assertTrue(TypeNameMappings.parse(NameMappingsFile.INITIAL_TEXT).resolve("example.Result", "createdDatetime").isEmpty());
    }
    @Test void explicitlySavesNewUtf8RulesAndBothReadersUseThem() throws Exception {
        var file = new NameMappingsFile(temp.resolve("config/name.properties"));
        String text = "# 日時の型\n.*Datetime$=java.time.LocalDateTime\n";
        var saved = file.save(file.read(), text); assertTrue(saved.exists()); assertEquals(text, Files.readString(file.path()));
        assertEquals("java.time.LocalDateTime", TypeNameMappings.read(file.path()).resolve("example.Result", "createdDatetime").orElseThrow());
        EntityDesc entity = new EntityDesc(); entity.setPackageName("example"); entity.setSimpleName("Result");
        assertEquals("java.time.LocalDateTime", new EntityPropertyClassNameResolver(file.path().toFile()).resolve(entity,"createdDatetime","java.sql.Timestamp"));
    }
    @Test void preservesCommentsRuleOrderRegexEscapesBomAndCrLfWithoutPropertiesReserialization() throws Exception {
        var file = new NameMappingsFile(temp.resolve("name.properties"));
        String text = "\uFEFF# コメント\r\nexample.Result@.*Datetime$=java.time.LocalDateTime\r\n.*Datetime$=java.sql.Timestamp\r\nflag\\d+=java.lang.Boolean\r\n";
        file.save(file.read(), text); assertEquals(text, file.read().source());
        var mappings = TypeNameMappings.read(file.path());
        assertEquals("java.time.LocalDateTime", mappings.resolve("example.Result","createdDatetime").orElseThrow());
        assertEquals("java.sql.Timestamp", mappings.resolve("example.Other","createdDatetime").orElseThrow());
        assertEquals("java.lang.Boolean", mappings.resolve("example.Result","flag1").orElseThrow());
    }
    @Test void rejectsInvalidRulesWithoutReplacingOrCreatingFiles() throws Exception {
        var file = new NameMappingsFile(temp.resolve("name.properties")); var missing = file.read();
        assertThrows(GenerationException.class, () -> file.save(missing,"[=java.lang.String\n")); assertFalse(Files.exists(file.path()));
        var before = file.save(missing, ".*Datetime$=java.time.LocalDateTime\n");
        for (String invalid : List.of("# comment\n[=java.lang.String\n", "code=String\n", "code\n")) {
            assertThrows(GenerationException.class, () -> file.save(before, invalid)); assertEquals(before, file.read());
        }
    }
    @Test void cannotOverwriteEditsMadeOnDiskAfterLoadingEvenIfTheTimestampMatches() throws Exception {
        var file = new NameMappingsFile(temp.resolve("name.properties")); var before = file.save(file.read(), "code=java.lang.String\n");
        Files.writeString(file.path(), "code=java.lang.Long\n"); Files.setLastModifiedTime(file.path(), before.modified());
        assertThrows(GenerationException.class, () -> file.save(before,"code=java.lang.Integer\n"));
        assertEquals("code=java.lang.Long\n", file.read().source());
    }
    @Test void preservesFilesCreatedOrDeletedAfterLoading() throws Exception {
        var file = new NameMappingsFile(temp.resolve("name.properties")); var missing = file.read();
        var existing = file.save(missing, "code=java.lang.String\n");
        assertThrows(GenerationException.class, () -> file.save(missing,"code=java.lang.Long\n"));
        Files.delete(file.path()); assertThrows(GenerationException.class, () -> file.save(existing,"code=java.lang.Long\n")); assertFalse(Files.exists(file.path()));
    }
    @Test void refusesSymlinksAndInvalidUtf8AndDoesNotChangeTheirContents() throws Exception {
        Path target = temp.resolve("actual.properties"); Files.writeString(target,"code=java.lang.String\n");
        Path link = Files.createSymbolicLink(temp.resolve("name.properties"), target);
        assertThrows(GenerationException.class, () -> new NameMappingsFile(link).read());
        assertEquals("code=java.lang.String\n", Files.readString(target));
        byte[] bad = {(byte)0xc3,(byte)0x28}; Files.write(target,bad);
        assertThrows(java.io.IOException.class, () -> new NameMappingsFile(target).read()); assertArrayEquals(bad,Files.readAllBytes(target));
    }
}
