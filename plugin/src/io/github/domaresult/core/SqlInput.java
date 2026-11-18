/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SELECT-only input check; SQL comments and Doma directives are not interpreted. */
public final class SqlInput {
    public enum Dialect {
        STANDARD(false, false, false), POSTGRESQL(true, false, true), MYSQL(false, true, false),
        MYSQL_NO_BACKSLASH(false, false, false), POSTGRESQL_LEGACY(true, true, true), SQLSERVER(true, false, false);
        final boolean nestedComments, backslashStrings, postgresEscapes;
        Dialect(boolean nestedComments, boolean backslashStrings, boolean postgresEscapes) {
            this.nestedComments = nestedComments;
            this.backslashStrings = backslashStrings;
            this.postgresEscapes = postgresEscapes;
        }
        public static Dialect fromJdbcUrl(String url) {
            String value = url == null ? "" : url.strip().toLowerCase(Locale.ROOT);
            if (value.startsWith("jdbc:postgresql:")) return POSTGRESQL;
            if (value.startsWith("jdbc:mysql:") || value.startsWith("jdbc:mariadb:")) return MYSQL;
            if (value.startsWith("jdbc:sqlserver:") || value.startsWith("jdbc:jtds:sqlserver:")) return SQLSERVER;
            return STANDARD;
        }
    }
    private static final Set<String> WRITES = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "CREATE",
            "ALTER", "DROP", "TRUNCATE", "CALL", "EXEC", "EXECUTE", "INTO", "GRANT", "REVOKE");
    private static final Pattern DOLLAR = Pattern.compile("\\$(?:[a-zA-Z_][a-zA-Z_0-9]*)?\\$");
    private record Replacement(int start, int end, String text) {}
    private SqlInput() {}

    public static String validate(String sql) {
        return validate(sql, Dialect.STANDARD);
    }

    public static String validate(String sql, Dialect dialect) { return inspect(sql, dialect); }

    static String forMetadata(String sql, Dialect dialect) { return validate(sql, dialect); }

    private static String inspect(String sql, Dialect dialect) {
        if (sql == null || sql.isBlank()) throw new GenerationException("Enter a SELECT statement.");
        List<String> words = new ArrayList<>();
        List<Replacement> replacements = new ArrayList<>();
        int terminal = -1;
        boolean started = false, leadingSeparator = false;
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
                    if (dialect.nestedComments && sql.startsWith("/*", i)) { depth++; i += 2; }
                    else if (sql.startsWith("*/", i)) { depth--; i += 2; }
                    else i++;
                }
                if (depth > 0) throw new GenerationException("Unclosed SQL comment.");
                continue;
            }
            if (terminal >= 0 && c == ';') {
                replacements.add(new Replacement(i, i + 1, ""));
                i++;
                continue; // Redundant trailing empty statements do not add another query.
            }
            if (terminal >= 0) throw new GenerationException("Only one SELECT statement is allowed.");
            if (c == ';' && dialect == Dialect.SQLSERVER && !started) {
                leadingSeparator = true;
                i++;
                continue;
            }
            started = true;
            if (c == ';') { terminal = i++; continue; }
            if (c == '?') throw new GenerationException("Use concrete SQL values instead of JDBC parameters (?). doma-gen does not take DAO arguments.");
            if (c == '\'' || c == '"' || c == '`' || c == '[') {
                int quotedStart = i;
                char close = c == '[' ? ']' : c;
                boolean escape = c == '\'' && (dialect.backslashStrings || dialect.postgresEscapes && i > 0
                        && (sql.charAt(i - 1) == 'e' || sql.charAt(i - 1) == 'E')
                        && (i < 2 || !Character.isJavaIdentifierPart(sql.charAt(i - 2))))
                        || c == '"' && dialect == Dialect.MYSQL;
                boolean closed = false;
                for (i++; i < sql.length(); i++) {
                    if (escape && sql.charAt(i) == '\\' && i + 1 < sql.length()) { i++; continue; }
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
                    int quotedStart = i;
                    i = end + delimiter.length();
                    continue;
                }
            }
            if (Character.isLetter(c) || c == '_') {
                int begin = i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i))
                        || sql.charAt(i) == '_' || sql.charAt(i) == '$')) i++;
                String word = sql.substring(begin, i).toUpperCase(Locale.ROOT);
                boolean locking = word.equals("UPDATE") && !words.isEmpty() && (words.get(words.size() - 1).equals("FOR")
                        || words.size() >= 3 && words.subList(words.size() - 3, words.size()).equals(List.of("FOR", "NO", "KEY")));
                if (WRITES.contains(word) && !locking) throw new GenerationException("Use a SELECT without data modification: " + word);
                words.add(word);
            } else i++;
        }
        if (words.isEmpty() || !(words.get(0).equals("SELECT") || words.get(0).equals("WITH"))
                || !words.contains("SELECT")) {
            throw new GenerationException("Enter a SELECT or a WITH ... SELECT statement.");
        }
        boolean startsWithCte = words.get(0).equals("WITH");
        if (leadingSeparator && !startsWithCte) {
            throw new GenerationException("A leading statement separator is supported only before a SQL Server WITH ... SELECT.");
        }
        if (terminal >= 0) replacements.add(new Replacement(terminal, terminal + 1, ""));
        return replace(sql, replacements);
    }

    private static String replace(String sql, List<Replacement> replacements) {
        replacements.sort(Comparator.comparingInt(Replacement::start));
        StringBuilder prepared = new StringBuilder(sql);
        for (int index = replacements.size() - 1; index >= 0; index--) {
            Replacement replacement = replacements.get(index);
            prepared.replace(replacement.start, replacement.end, replacement.text);
        }
        return prepared.toString();
    }

}
