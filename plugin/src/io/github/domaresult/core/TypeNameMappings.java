/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Ordered, UTF-8 property-name rules, following Doma-Gen's resolver conventions. */
public final class TypeNameMappings {
    private static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "short", "int", "long", "char", "float", "double");
    private static final TypeNameMappings EMPTY = new TypeNameMappings(List.of());
    private record Rule(Pattern pattern, String replacement, boolean qualified, int line) {}
    private final List<Rule> rules;

    private TypeNameMappings(List<Rule> rules) { this.rules = List.copyOf(rules); }

    public static TypeNameMappings empty() { return EMPTY; }

    public static TypeNameMappings read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static TypeNameMappings parse(String text) {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<Rule> rules = new ArrayList<>();
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            // A Java type/replacement cannot contain '='; regex lookarounds can.
            int separator = line.lastIndexOf('=');
            if (separator <= 0 || separator == line.length() - 1) {
                throw error(i + 1, "Expected property-name-regex=fully.qualified.JavaType.");
            }
            String key = line.substring(0, separator).trim();
            String replacement = line.substring(separator + 1).trim();
            int scope = key.indexOf('@');
            boolean qualified = scope > 0;
            String regex = qualified ? Pattern.quote(key.substring(0, scope)) + key.substring(scope)
                    : scope == 0 ? key.substring(1) : key;
            if (regex.isEmpty() || replacement.isEmpty()) throw error(i + 1, "The pattern and Java type must not be empty.");
            try {
                Pattern pattern = Pattern.compile(regex);
                // Capture references are validated after expansion for a matching property.
                if (!replacement.contains("$") && !replacement.contains("\\")) validateType(replacement, i + 1);
                rules.add(new Rule(pattern, replacement, qualified, i + 1));
            } catch (PatternSyntaxException e) {
                throw error(i + 1, "Invalid regular expression: " + e.getDescription());
            }
        }
        return new TypeNameMappings(rules);
    }

    /** Matches the final generated Java property name, case-sensitively; first match wins. */
    public Optional<String> resolve(String qualifiedClassName, String propertyName) {
        for (Rule rule : rules) {
            String input = rule.qualified ? qualifiedClassName + "@" + propertyName : propertyName;
            var matcher = rule.pattern.matcher(input);
            if (!matcher.matches()) continue;
            String type;
            try {
                StringBuilder replacement = new StringBuilder();
                matcher.appendReplacement(replacement, rule.replacement);
                type = replacement.toString();
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                throw error(rule.line, "Invalid capture-group replacement for property " + propertyName + ".");
            }
            validateType(type, rule.line);
            return Optional.of(type);
        }
        return Optional.empty();
    }

    private static void validateType(String type, int line) {
        if (PRIMITIVES.contains(type) || type.equals("byte[]")) return;
        if (!type.contains(".")) throw error(line, "Use a fully qualified Java type, a primitive, or byte[].");
        for (String part : type.split("\\.", -1)) {
            if (!JavaNames.isIdentifier(part)) throw error(line, "Invalid Java type name.");
        }
    }

    private static GenerationException error(int line, String message) {
        return new GenerationException("Type mappings, line " + line + ": " + message);
    }
}
