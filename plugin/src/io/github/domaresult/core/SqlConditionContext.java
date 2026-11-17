/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/** Recognizes balanced filter-only Doma conditions; their SQL remains ordinary test SQL. */
final class SqlConditionContext {
    private enum Clause { OTHER, WHERE, HAVING }
    private static final Set<String> BOUNDARIES = Set.of("FROM", "WHERE", "HAVING", "GROUP", "ORDER",
            "UNION", "EXCEPT", "INTERSECT", "MINUS", "WINDOW", "QUALIFY", "LIMIT", "OFFSET", "FETCH",
            "FOR", "RETURNING", "OPTION", "CONNECT", "START", "MODEL");
    private static final class Query {
        final int depth;
        Clause clause = Clause.OTHER;
        Query(int depth) { this.depth = depth; }
    }
    private record Condition(Query query, int depth, int offset) {}
    private final String sql;
    private final Deque<Query> queries = new ArrayDeque<>();
    private final Deque<Condition> conditions = new ArrayDeque<>();
    private int depth;
    private boolean identifierPrefix;

    SqlConditionContext(String sql) { this.sql = sql; }

    void word(String word, int offset) {
        if (identifierPrefix) { identifierPrefix = false; return; }
        Query query = queries.peek();
        if (word.equals("SELECT")) {
            if (query != null && query.depth == depth) boundary(query, offset);
            while (!queries.isEmpty() && queries.peek().depth >= depth) queries.pop();
            queries.push(new Query(depth));
        } else if (query != null && query.depth == depth && BOUNDARIES.contains(word)) {
            boundary(query, offset);
            query.clause = word.equals("WHERE") ? Clause.WHERE : word.equals("HAVING") ? Clause.HAVING : Clause.OTHER;
        }
    }

    void atom() { identifierPrefix = false; }

    void symbol(char symbol, int offset) {
        identifierPrefix = symbol == '.' || symbol == '@' || symbol == '#' || symbol == '$';
        if (symbol == '(') depth++;
        else if (symbol == ')') {
            depth--;
            if (!conditions.isEmpty() && depth < conditions.peek().depth()) {
                throw error(offset, "Doma %if and %end must stay in the same parenthesis scope.");
            }
            while (!queries.isEmpty() && queries.peek().depth > depth) queries.pop();
        } else if (symbol == ';' && !conditions.isEmpty()) {
            throw error(offset, "Close the Doma %if with %end before the statement terminator.");
        }
    }

    void directive(String comment, int offset) {
        if (comment.equals("%if") || comment.startsWith("%if") && comment.length() > 3 && Character.isWhitespace(comment.charAt(3))) {
            if (comment.substring(3).isBlank()) throw error(offset, "A Doma %if requires a condition expression.");
            Query query = queries.peek();
            if (query == null || query.clause == Clause.OTHER) {
                throw error(offset, "Automatic Doma conditions are supported only inside an existing WHERE or HAVING clause. "
                        + "Replace conditions affecting SELECT columns or other clauses with a concrete SELECT in Metadata SQL.");
            }
            conditions.push(new Condition(query, depth, offset));
        } else if (comment.equals("%end")) {
            if (conditions.isEmpty()) throw error(offset, "Doma %end has no matching %if.");
            Condition condition = conditions.peek();
            if (condition.query() != queries.peek() || condition.depth() != depth) {
                throw error(offset, "Doma %if and %end must stay in the same query and parenthesis scope.");
            }
            conditions.pop();
        } else {
            throw error(offset, "Replace %else/%elseif branches, loops and other dynamic Doma directives with a concrete SELECT in Metadata SQL. "
                    + "Nested %if/%end filters in WHERE/HAVING, simple %expand and bind comments with test literals are supported.");
        }
    }

    void finish() {
        if (!conditions.isEmpty()) throw error(conditions.peek().offset(), "Doma %if has no matching %end.");
    }

    private void boundary(Query query, int offset) {
        for (Condition condition : conditions) {
            if (condition.query() == query) {
                throw error(offset, "A Doma filter condition cannot cross a SQL clause or SELECT boundary. "
                        + "Keep %if/%end within WHERE or HAVING, or prepare a concrete SELECT in Metadata SQL.");
            }
        }
    }

    private GenerationException error(int offset, String message) {
        int line = 1;
        for (int i = 0; i < offset; i++) if (sql.charAt(i) == '\n') line++;
        return new GenerationException(message + " (line " + line + ")");
    }
}
