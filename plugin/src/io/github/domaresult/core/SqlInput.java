/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.seasar.doma.internal.expr.Value;
import org.seasar.doma.internal.expr.util.ExpressionUtil;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates SELECT input and protects database quoting while Doma evaluates the template. */
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
    private record Validated(String sql, String maskedSql, Map<String, String> originals, boolean startsWithCte) {}

    /** Protects database-specific quoting/comments while Doma assembles the SQL structure. */
    private static final class Masks {
        final List<Replacement> replacements = new ArrayList<>();
        final Map<String, String> originals = new LinkedHashMap<>();
        private final String prefix;
        Masks(String sql) {
            String candidate = "__doma_result_" + java.util.UUID.randomUUID().toString().replace("-", "") + "_";
            while (sql.contains(candidate)) candidate += "_";
            prefix = candidate;
        }
        void token(String sql, int start, int end, boolean literal, boolean comment) {
            String key = prefix + originals.size() + "__";
            if (literal) key = "'" + key + "'";
            if (comment) key = "/*!" + key + "\n".repeat((int) sql.substring(start, end).chars().filter(c -> c == '\n').count()) + "*/";
            originals.put(key, sql.substring(start, end));
            replacements.add(new Replacement(start, end, key));
        }
    }

    private SqlInput() {}

    public static String validate(String sql) {
        return validate(sql, Dialect.STANDARD);
    }

    public static String validate(String sql, Dialect dialect) {
        Validated validated = inspect(sql, dialect);
        DomaSql.parse(validated.maskedSql());
        return validated.sql();
    }

    public static DomaPreparedSelect prepare(String sql, Dialect dialect, Map<String, Value> arguments,
            ClassLoader loader, String jdbcUrl) {
        Validated validated = inspect(sql, dialect);
        return DomaSql.prepare(validated.maskedSql(), validated.originals(), arguments, loader, dialect, jdbcUrl);
    }

    static String forMetadata(String sql, Dialect dialect) {
        return prepare(sql, dialect, Map.of(), SqlInput.class.getClassLoader(), "").sql();
    }

    static String jdbcSql(String sql, Dialect dialect) {
        Validated validated = inspect(sql, dialect, true);
        return dialect == Dialect.SQLSERVER && validated.startsWithCte() ? ";" + validated.sql() : validated.sql();
    }

    private static Validated inspect(String sql, Dialect dialect) { return inspect(sql, dialect, false); }

    private static Validated inspect(String sql, Dialect dialect, boolean boundSql) {
        if (sql == null || sql.isBlank()) throw new GenerationException("Enter a SELECT statement.");
        List<String> words = new ArrayList<>();
        List<Replacement> replacements = new ArrayList<>();
        Masks masks = new Masks(sql);
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
                String comment = sql.substring(start + 2, i - 2).strip();
                char marker = start + 2 < sql.length() ? sql.charAt(start + 2) : '*';
                if (comment.startsWith("%") || comment.startsWith("#") || comment.startsWith("^")
                        || ExpressionUtil.isExpressionIdentifierStart(marker)) {
                    if (terminal >= 0) throw new GenerationException("Only one SELECT statement is allowed.");
                    // All Doma expressions are left to the unmodified official parser and builder.
                } else masks.token(sql, start, i, false, true);
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
                masks.replacements.add(new Replacement(i, i + 1, ""));
                i++;
                continue;
            }
            started = true;
            if (c == ';') { terminal = i++; continue; }
            if (c == '?' && !boundSql) throw new GenerationException("Use Doma bind comments with typed values from Arguments instead of JDBC parameters (?).");
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
                masks.token(sql, quotedStart, i, c == '\'' || c == '"' && dialect == Dialect.MYSQL, false);
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
                    masks.token(sql, quotedStart, i, true, false);
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
        List<Replacement> maskedReplacements = new ArrayList<>(replacements);
        maskedReplacements.addAll(masks.replacements);
        return new Validated(replace(sql, replacements), replace(sql, maskedReplacements), masks.originals, startsWithCte);
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
