/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TypeNameMappingsTest {
    @TempDir Path directory;

    @Test void userRuleMatchesGeneratedCamelCaseNameAndOverridesJdbcType() throws Exception {
        Path file = directory.resolve("name.properties");
        Files.writeString(file, ".*Datetime$=java.time.LocalDateTime\n", StandardCharsets.UTF_8);
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID())) {
            var columns = new JdbcMetadataReader().read(connection,
                    "select date '2024-01-02' as CREATED_DATETIME, date '2024-01-03' as OTHER_DATE",
                    "example.Result", TypeNameMappings.read(file));
            assertEquals(List.of(new ResultColumn("CREATED_DATETIME", "java.time.LocalDateTime"),
                    new ResultColumn("OTHER_DATE", "java.time.LocalDate")), columns);
            String source = new EntityGenerator().generate("example", "Result", columns);
            assertTrue(source.contains("import java.time.LocalDateTime;"));
            assertTrue(source.contains("private LocalDateTime createdDatetime;"));
            assertTrue(source.contains("@Column(name = \"CREATED_DATETIME\")"));
        }
    }

    @Test void firstMatchingRuleWinsInFileOrder() {
        var rules = TypeNameMappings.parse(".*=java.lang.String\n.*Datetime$=java.time.LocalDateTime\n");
        assertEquals("java.lang.String", rules.resolve("example.Result", "createdDatetime").orElseThrow());
    }

    @Test void classScopedRuleQuotesTheClassPrefixLiterally() {
        var rules = TypeNameMappings.parse("com.example.Result@.*Datetime$=java.time.LocalDateTime\n");
        assertEquals("java.time.LocalDateTime", rules.resolve("com.example.Result", "createdDatetime").orElseThrow());
        assertTrue(rules.resolve("comXexample.Result", "createdDatetime").isEmpty());
        assertTrue(rules.resolve("com.example.Other", "createdDatetime").isEmpty());
    }

    @Test void acceptsCommentsBlankLinesWhitespaceBomAndCrLf() {
        var rules = TypeNameMappings.parse("\uFEFF# comment\r\n ! comment\r\n\r\n .*Datetime$ = java.time.LocalDateTime \r\n");
        assertEquals("java.time.LocalDateTime", rules.resolve("example.Result", "createdDatetime").orElseThrow());
        assertTrue(TypeNameMappings.parse("  \n# comment\n").resolve("example.Result", "name").isEmpty());
    }

    @Test void matchingIsCompleteAndCaseSensitive() {
        var rules = TypeNameMappings.parse("Datetime=java.time.LocalDateTime\n");
        assertTrue(rules.resolve("example.Result", "createdDatetime").isEmpty());
        assertTrue(rules.resolve("example.Result", "datetime").isEmpty());
        assertTrue(TypeNameMappings.parse(".*Datetime$=java.time.LocalDateTime\n")
                .resolve("example.Result", "createdDateTime").isEmpty());
    }

    @Test void captureGroupsAndLeadingAtSignFollowOfficialResolverConventions() {
        var rules = TypeNameMappings.parse("@.*(Date)time$=java.time.Local$1Time\n");
        assertEquals("java.time.LocalDateTime", rules.resolve("example.Result", "createdDatetime").orElseThrow());
    }

    @Test void preservesRegexBackslashesInsteadOfPropertiesUnescaping() {
        var rules = TypeNameMappings.parse("^created\\p{Lu}.*$=java.time.LocalDateTime\n");
        assertEquals("java.time.LocalDateTime", rules.resolve("example.Result", "createdDatetime").orElseThrow());
    }

    @Test void mapsUtf8PropertyNamesAndQualifiedDomainNames() throws Exception {
        Path file = directory.resolve("日本語.properties");
        Files.writeString(file, "日本語=com.example.domain.日本語型\n", StandardCharsets.UTF_8);
        assertEquals("com.example.domain.日本語型", TypeNameMappings.read(file)
                .resolve("example.Result", "日本語").orElseThrow());
    }

    @Test void resolvesNamesAfterAccessorCollisionDisambiguation() throws Exception {
        var rules = TypeNameMappings.parse("userId2=java.lang.Long\n");
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID())) {
            var columns = new JdbcMetadataReader().read(connection, "select 1 as USER_ID, 2 as \"user-id\"",
                    "example.Result", rules);
            assertEquals("java.lang.Integer", columns.get(0).javaType());
            assertEquals("java.lang.Long", columns.get(1).javaType());
            assertTrue(new EntityGenerator().generate("example", "Result", columns).contains("private Long userId2;"));
        }
    }

    @Test void overrideTakesPrecedenceEvenForAnUnsupportedJdbcType() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID())) {
            String sql = "select ARRAY[1,2] as identifiers";
            var rules = TypeNameMappings.parse("identifiers=java.lang.String\n");
            assertEquals("java.lang.String", new JdbcMetadataReader().read(connection, sql, "example.Result", rules).get(0).javaType());
            assertThrows(MetadataException.class, () -> new JdbcMetadataReader().read(connection, sql));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "broken", "=java.lang.String", "name=", "[=java.lang.String", "name=String",
            "name=java.lang.String;system()", "name=java.util.List<java.lang.String>", "name=void", "@=java.lang.String" })
    void malformedRulesShowTheirLineNumber(String line) {
        var error = assertThrows(GenerationException.class, () -> TypeNameMappings.parse("# comment\n" + line));
        assertTrue(error.getMessage().contains("line 2"));
    }

    @Test void invalidCaptureReplacementIsAnActionableValidationError() {
        var rules = TypeNameMappings.parse("# comment\n.*Datetime$=java.time.Local$2\n");
        var error = assertThrows(GenerationException.class, () -> rules.resolve("example.Result", "createdDatetime"));
        assertTrue(error.getMessage().contains("line 2"));
    }

    @Test void expandedTypesCannotInjectSourceCode() {
        var rules = TypeNameMappings.parse(".*=java.lang.String$0\n");
        assertThrows(GenerationException.class, () -> rules.resolve("example.Result", "; injected"));
    }

    @Test void acceptsPrimitiveAndByteArrayOverrides() {
        var rules = TypeNameMappings.parse("count=int\nbody=byte[]\n");
        assertEquals("int", rules.resolve("example.Result", "count").orElseThrow());
        assertEquals("byte[]", rules.resolve("example.Result", "body").orElseThrow());
    }
}
