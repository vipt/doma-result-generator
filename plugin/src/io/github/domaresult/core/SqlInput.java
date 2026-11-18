/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prepares metadata SELECTs and accepts filter-only conditions without evaluating expressions. */
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
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\\\r\\n]|\\\\[btnfr\"'\\\\])*\"");
    private static final String IDENTIFIER = "(?:[\\p{L}_][\\p{L}\\p{N}_$#]*|\"(?:[^\"]|\"\")+\"|`(?:[^`]|``)+`|\\[(?:[^\\]]|\\]\\])+\\])";
    private static final Pattern QUALIFIER = Pattern.compile(IDENTIFIER + "(?:\\." + IDENTIFIER + ")*");
    private record Replacement(int start, int end, String text) {}
    private record Validated(String sql, String maskedSql, Map<String, String> originals, boolean startsWithCte) {}

    /** Protects database-specific quoting/comments while Doma assembles the SQL structure. */
    private static final class Masks {
        final List<Replacement> replacements = new ArrayList<>();
        final Map<String, String> originals = new LinkedHashMap<>();
        private final String prefix;
        Masks(String sql) {
            String candidate = "__doma_result_opaque_";
            while (sql.contains(candidate)) candidate += "_";
            prefix = candidate;
        }
        void token(String sql, int start, int end, boolean literal, boolean comment) {
            String key = prefix + originals.size() + "__";
            if (literal) key = "'" + key + "'";
            if (comment) key = "--" + key;
            originals.put(key, sql.substring(start, end));
            replacements.add(new Replacement(start, end, key + (comment ? "\n" : "")));
        }
    }

    private SqlInput() {}

    public static String validate(String sql) {
        return validate(sql, Dialect.STANDARD);
    }

    public static String validate(String sql, Dialect dialect) {
        return inspect(sql, dialect).sql();
    }

    /** SQL Server's JDBC getMetaData prepends SET FMTONLY ON without a terminator. */
    static String forMetadata(String sql, Dialect dialect) {
        Validated validated = inspect(sql, dialect);
        String prepared = DomaTestSqlBuilder.prepare(validated.maskedSql(), validated.originals());
        return dialect == Dialect.SQLSERVER && validated.startsWithCte() ? ";" + prepared : prepared;
    }

    private static Validated inspect(String sql, Dialect dialect) {
        if (sql == null || sql.isBlank()) throw new GenerationException("Enter a SELECT statement.");
        List<String> words = new ArrayList<>();
        List<Replacement> replacements = new ArrayList<>();
        Masks masks = new Masks(sql);
        SqlConditionContext conditions = new SqlConditionContext(sql);
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
                if (comment.equals("%expand") || comment.startsWith("%expand")
                        && comment.length() > 7 && Character.isWhitespace(comment.charAt(7))) {
                    if (terminal >= 0) throw new GenerationException("Only one SELECT statement is allowed.");
                    int star = i;
                    while (star < sql.length() && Character.isWhitespace(sql.charAt(star))) star++;
                    if (star >= sql.length() || sql.charAt(star) != '*') {
                        throw new GenerationException("A Doma %expand directive must be followed by *. Check Metadata SQL.");
                    }
                    replacements.add(new Replacement(start, star + 1, expansion(comment.substring(7).strip())));
                    started = true;
                    i = star + 1;
                } else if (comment.startsWith("%")) {
                    if (terminal >= 0) throw new GenerationException("Only one SELECT statement is allowed.");
                    conditions.directive(comment, start);
                } else if (comment.startsWith("#") || comment.startsWith("^")) {
                    throw new GenerationException("Replace embedded or literal Doma directives with a concrete SELECT in Metadata SQL. "
                            + "Filter-only %if/%end conditions and bind comments with test literals (/* id */0) are supported.");
                } else masks.token(sql, start, i, false, true);
                continue;
            }
            if (terminal >= 0) throw new GenerationException("Only one SELECT statement is allowed.");
            if (c == ';' && dialect == Dialect.SQLSERVER && !started) {
                leadingSeparator = true;
                masks.replacements.add(new Replacement(i, i + 1, ""));
                i++;
                continue;
            }
            started = true;
            if (c == ';') { conditions.symbol(c, i); terminal = i++; continue; }
            if (c == '?') throw new GenerationException("Replace JDBC parameters (?) with typed test literals.");
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
                conditions.atom();
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
                    conditions.atom(); continue;
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
                conditions.word(word, begin);
                words.add(word);
            } else { conditions.symbol(c, i); i++; }
        }
        conditions.finish();
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

    private static String expansion(String argument) {
        if (argument.isEmpty()) return "*";
        if (!STRING_LITERAL.matcher(argument).matches()) {
            throw new GenerationException("The %expand alias must be a quoted string literal, for example /*%expand \"e\" */*. "
                    + "Replace alias expressions with a concrete SELECT in Metadata SQL.");
        }
        String alias = argument.substring(1, argument.length() - 1).translateEscapes();
        if (alias.isEmpty()) return "*";
        if (alias.chars().anyMatch(Character::isISOControl) || !QUALIFIER.matcher(alias).matches()) {
            throw new GenerationException("The %expand alias must be a table identifier or quoted table identifier. Check Metadata SQL.");
        }
        return alias + ".*";
    }
}
