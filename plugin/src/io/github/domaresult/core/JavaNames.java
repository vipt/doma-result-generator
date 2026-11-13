/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.lang.model.SourceVersion;

public final class JavaNames {
    private static final Set<String> RESTRICTED = Set.of("var", "yield", "record", "sealed", "permits");

    private JavaNames() {}

    public static boolean isIdentifier(String value) {
        return value != null && SourceVersion.isIdentifier(value)
                && !SourceVersion.isKeyword(value) && !RESTRICTED.contains(value);
    }

    public static void validateTarget(String packageName, String className) {
        if (packageName == null || packageName.isBlank()) {
            throw new GenerationException("Enter a named Java package (for example, com.example.dto).");
        }
        for (String part : packageName.split("\\.", -1)) {
            if (!isIdentifier(part)) {
                throw new GenerationException("Invalid Java package: " + packageName);
            }
        }
        if (!isIdentifier(className)) {
            throw new GenerationException("Invalid Java class name: " + className);
        }
        try {
            Class.forName("java.lang." + className, false, null);
            throw new GenerationException("Choose a class name such as " + className
                    + "Result to avoid shadowing java.lang types in Doma-generated code.");
        } catch (ClassNotFoundException expected) {
            // The result class does not shadow a java.lang type.
        }
    }

    public static String fieldName(String label) {
        String input = label.equals(label.toUpperCase(Locale.ROOT))
                ? label.toLowerCase(Locale.ROOT) : label;
        StringBuilder name = new StringBuilder();
        boolean upperNext = false;
        for (int offset = 0; offset < input.length();) {
            int cp = input.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == '_' || !Character.isJavaIdentifierPart(cp)) {
                upperNext = name.length() > 0;
                continue;
            }
            if (name.length() == 0) {
                if (!Character.isJavaIdentifierStart(cp)) name.append("column");
                name.appendCodePoint(Character.toLowerCase(cp));
            } else {
                name.appendCodePoint(upperNext ? Character.toUpperCase(cp) : cp);
            }
            upperNext = false;
        }
        if (name.length() == 0) name.append("column");
        if (!isIdentifier(name.toString())) name.append("Value");
        return name.toString();
    }

    /** Shared by metadata type resolution and source generation so rules see the exact final names. */
    public static List<String> fieldNames(List<String> labels) {
        Set<String> seenLabels = new HashSet<>();
        Set<String> accessors = new HashSet<>();
        List<String> fields = new ArrayList<>();
        for (String label : labels) {
            ResultColumn.validateLabel(label);
            if (!seenLabels.add(label.toLowerCase(Locale.ROOT))) {
                throw new GenerationException("Duplicate result label: " + label
                        + ". Use unique AS aliases in the SELECT.");
            }
            String base = fieldName(label);
            String field = base;
            int suffix = 2;
            while (!accessors.add(accessorSuffix(field))) field = base + suffix++;
            fields.add(field);
        }
        return List.copyOf(fields);
    }

    static String accessorSuffix(String field) {
        int first = field.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(first)))
                + field.substring(Character.charCount(first));
    }
}
