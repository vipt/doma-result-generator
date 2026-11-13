/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates concrete metadata SQL; deliberately does not evaluate Doma expressions. */
public final class SqlInput {
    private static final Set<String> WRITES = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "CREATE",
            "ALTER", "DROP", "TRUNCATE", "CALL", "EXEC", "EXECUTE", "INTO", "GRANT", "REVOKE");
    private static final Pattern DOLLAR = Pattern.compile("\\$(?:[a-zA-Z_][a-zA-Z_0-9]*)?\\$");

    private SqlInput() {}

    public static String validate(String sql) {
        if (sql == null || sql.isBlank()) throw new GenerationException("Enter a SELECT statement.");
        List<String> words = new ArrayList<>();
        int terminal = -1;
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (sql.startsWith("--", i)) {
                int end = sql.indexOf('\n', i + 2);
                i = end < 0 ? sql.length() : end + 1;
                continue;
            }
            if (sql.startsWith("/*", i)) {
                int start = i;
                int depth = 1;
                i += 2;
                while (i < sql.length() && depth > 0) {
                    if (sql.startsWith("/*", i)) { depth++; i += 2; }
                    else if (sql.startsWith("*/", i)) { depth--; i += 2; }
                    else i++;
                }
                if (depth > 0) throw new GenerationException("Unclosed SQL comment.");
                String comment = sql.substring(start + 2, i - 2).stripLeading();
                if (comment.startsWith("%") || comment.startsWith("#") || comment.startsWith("^")) {
                    throw new GenerationException("Replace Doma dynamic/expand directives with concrete metadata SQL. "
                            + "Bind comments with test literals (/* id */0) can remain.");
                }
                continue;
            }
            if (terminal >= 0) throw new GenerationException("Only one SELECT statement is allowed.");
            if (c == ';') { terminal = i++; continue; }
            if (c == '?') throw new GenerationException("Replace JDBC parameters (?) with typed test literals.");
            if (c == '\'' || c == '"' || c == '`' || c == '[') {
                char close = c == '[' ? ']' : c;
                boolean closed = false;
                for (i++; i < sql.length(); i++) {
                    if (sql.charAt(i) == close) {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == close) { i++; continue; }
                        i++; closed = true; break;
                    }
                }
                if (!closed) throw new GenerationException("Unclosed SQL string or quoted identifier.");
                continue;
            }
            if (c == '$') {
                Matcher matcher = DOLLAR.matcher(sql).region(i, sql.length());
                if (matcher.lookingAt()) {
                    String delimiter = matcher.group();
                    int end = sql.indexOf(delimiter, matcher.end());
                    if (end < 0) throw new GenerationException("Unclosed dollar-quoted string.");
                    i = end + delimiter.length(); continue;
                }
            }
            if (Character.isLetter(c) || c == '_') {
                int begin = i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i))
                        || sql.charAt(i) == '_' || sql.charAt(i) == '$')) i++;
                String word = sql.substring(begin, i).toUpperCase(Locale.ROOT);
                if (WRITES.contains(word)) throw new GenerationException("Use a SELECT without data modification: " + word);
                words.add(word);
            } else i++;
        }
        if (words.isEmpty() || !(words.get(0).equals("SELECT") || words.get(0).equals("WITH"))
                || !words.contains("SELECT")) {
            throw new GenerationException("Enter a SELECT or a WITH ... SELECT statement.");
        }
        return terminal < 0 ? sql : sql.substring(0, terminal) + sql.substring(terminal + 1);
    }
}
