/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.sql.DataSource;
import org.seasar.doma.DomaException;
import org.seasar.doma.internal.expr.ExpressionParser;
import org.seasar.doma.internal.expr.node.EmptyNode;
import org.seasar.doma.internal.jdbc.sql.NodePreparedSqlBuilder;
import org.seasar.doma.internal.jdbc.sql.SqlParser;
import org.seasar.doma.internal.jdbc.sql.node.BindVariableNode;
import org.seasar.doma.internal.jdbc.sql.node.EmbeddedVariableNode;
import org.seasar.doma.internal.jdbc.sql.node.IfBlockNode;
import org.seasar.doma.internal.jdbc.sql.node.ForBlockNode;
import org.seasar.doma.internal.jdbc.sql.node.ClauseNode;
import org.seasar.doma.internal.jdbc.sql.node.WhereClauseNode;
import org.seasar.doma.internal.jdbc.sql.node.HavingClauseNode;
import org.seasar.doma.internal.jdbc.sql.node.SelectStatementNode;
import org.seasar.doma.internal.jdbc.sql.node.ParensNode;
import org.seasar.doma.internal.jdbc.sql.node.SqlLocation;
import org.seasar.doma.jdbc.JdbcException;
import org.seasar.doma.jdbc.Config;
import org.seasar.doma.jdbc.SqlKind;
import org.seasar.doma.jdbc.SqlNode;
import org.seasar.doma.jdbc.SqlLogType;
import org.seasar.doma.jdbc.dialect.Dialect;
import org.seasar.doma.jdbc.dialect.StandardDialect;

/** Official Doma assembly with test literals and explicit branch choices, never application evaluation. */
final class DomaTestSqlBuilder extends NodePreparedSqlBuilder {
    static final int MAX_VARIANTS = 64;
    private static final Config CONFIG = new Config() {
        @Override public DataSource getDataSource() { throw new UnsupportedOperationException("SQL assembly does not connect to a database."); }
        @Override public Dialect getDialect() { return new StandardDialect(); }
    };

    private record Choice(List<String> expressions, boolean loop) {}
    private final Map<SqlNode, Choice> branchKeys;
    private final Map<Choice, Integer> choices;
    private final DomaTestExpressionEvaluator testEvaluator;

    private DomaTestSqlBuilder(Map<SqlNode, Choice> branchKeys, Map<Choice, Integer> choices) {
        this(branchKeys, choices, new DomaTestExpressionEvaluator());
    }

    private DomaTestSqlBuilder(Map<SqlNode, Choice> branchKeys, Map<Choice, Integer> choices,
            DomaTestExpressionEvaluator evaluator) {
        super(CONFIG, SqlKind.SELECT, null, evaluator, SqlLogType.FORMATTED);
        this.branchKeys = branchKeys;
        this.choices = choices;
        this.testEvaluator = evaluator;
    }

    static void validate(String maskedSql) { parse(maskedSql); }

    static List<String> prepareVariants(String maskedSql, Map<String, String> originals) {
        try {
            SqlNode root = parse(maskedSql);
            Map<SqlNode, Choice> keys = branchKeys(root);
            Map<Choice, Integer> counts = new LinkedHashMap<>();
            for (var key : keys.values()) {
                // Identical condition chains in UNION branches use the same representative choice.
                counts.put(key, key.loop() ? 2 : key.expressions().size() + 1); // ELSE/empty, or 1/2 sample iterations.
            }
            List<Map<Choice, Integer>> assignments = new ArrayList<>();
            assignments.add(Map.of());
            for (var entry : counts.entrySet()) {
                if (assignments.size() > MAX_VARIANTS / entry.getValue()) {
                    throw new GenerationException("Metadata SQL has more than " + MAX_VARIANTS
                            + " conditional branch combinations. Use a concrete SELECT in Metadata SQL.");
                }
                List<Map<Choice, Integer>> expanded = new ArrayList<>();
                for (var assignment : assignments) {
                    for (int choice = 0; choice < entry.getValue(); choice++) {
                        var next = new LinkedHashMap<>(assignment);
                        next.put(entry.getKey(), choice);
                        expanded.add(next);
                    }
                }
                assignments = expanded;
            }
            var variants = new LinkedHashSet<String>();
            for (var assignment : assignments) {
                String sql = new DomaTestSqlBuilder(keys, assignment).build(root, Function.identity()).getRawSql();
                for (var entry : originals.entrySet()) {
                    sql = sql.replace(entry.getKey(), entry.getValue());
                }
                variants.add(sql);
            }
            return List.copyOf(variants);
        } catch (GenerationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GenerationException("Doma could not assemble the metadata test SQL. Check directive nesting and test literals in Metadata SQL.");
        }
    }

    private static SqlNode parse(String sql) {
        try {
            SqlNode root = new SqlParser(sql).parse();
            inspectExpressions(root);
            return root;
        } catch (GenerationException e) { throw e; }
        catch (DomaException e) {
            String code = e.getMessageResource().getCode();
            Object args = e.getArgs();
            String line = e instanceof JdbcException && args instanceof Object[] values
                    && values.length > 1 && values[1] instanceof Integer number
                    ? " (prepared SQL line " + number + ")" : "";
            throw new GenerationException("Doma could not parse the metadata test SQL: " + code + line
                    + ". Check directive nesting and test literals in Metadata SQL.");
        }
    }

    private static void inspectExpressions(SqlNode node) {
        if (node instanceof ForBlockNode block) {
            inspectExpression(block.getForNode().getExpression(), block.getForNode().getLocation());
        }
        if (node instanceof EmbeddedVariableNode embedded) {
            inspectExpression(embedded.getVariableName(), embedded.getLocation());
            if (!DomaTestExpressionEvaluator.isClosed(embedded.getVariableName(), Set.of())) {
                throw new GenerationException("Embedded SQL needs a constant expression for metadata lookup. "
                        + "Use concrete SQL in Metadata SQL for runtime fragments; application code is not evaluated.");
            }
        }
        if (node instanceof IfBlockNode block) {
            inspectExpression(block.getIfNode().getExpression(), block.getIfNode().getLocation());
            for (var alternative : block.getElseifNodes()) inspectExpression(alternative.getExpression(), alternative.getLocation());
        }
        for (SqlNode child : node.getChildren()) inspectExpressions(child);
    }

    private static void inspectExpression(String expression, SqlLocation location) {
        String line = " (prepared SQL line " + location.getLineNumber() + ")";
        try {
            if (new ExpressionParser(expression).parse() instanceof EmptyNode) {
                throw new GenerationException("A Doma condition requires an expression" + line + ". Check Metadata SQL.");
            }
        } catch (DomaException e) {
            throw new GenerationException("Doma could not parse a condition expression: " + e.getMessageResource().getCode()
                    + line + ". Check Metadata SQL.");
        }
    }

    private static Map<SqlNode, Choice> branchKeys(SqlNode root) {
        Map<SqlNode, Choice> keys = new LinkedHashMap<>();
        collect(root, false, keys, Set.of());
        return keys;
    }

    private static void collect(SqlNode node, boolean filter, Map<SqlNode, Choice> keys, Set<String> loopFlags) {
        if (node instanceof SelectStatementNode || node instanceof ClauseNode) {
            filter = node instanceof WhereClauseNode || node instanceof HavingClauseNode;
        }
        if (node instanceof ForBlockNode block) {
            var loop = block.getForNode();
            if (!filter || containsClause(loop)) keys.put(block, new Choice(List.of(loop.getExpression().strip()), true));
            var locals = new LinkedHashSet<>(loopFlags);
            locals.add(loop.getIdentifier() + "_has_next");
            locals.add(loop.getIdentifier() + "_index");
            collect(loop, filter, keys, locals);
            collect(block.getEndNode(), filter, keys, loopFlags);
            return;
        }
        if (node instanceof IfBlockNode block && !loopCondition(block, loopFlags)
                && !(filter && block.getElseifNodes().isEmpty()
                && block.getElseNode() == null && !containsClause(block.getIfNode()))) {
            List<String> expressions = new ArrayList<>();
            expressions.add(block.getIfNode().getExpression().strip());
            block.getElseifNodes().forEach(alternative -> expressions.add(alternative.getExpression().strip()));
            keys.put(block, new Choice(List.copyOf(expressions), false));
        }
        for (SqlNode child : node.getChildren()) collect(child, filter, keys, loopFlags);
    }

    private static boolean loopCondition(IfBlockNode block, Set<String> names) {
        return !names.isEmpty() && DomaTestExpressionEvaluator.isClosed(block.getIfNode().getExpression(), names)
                && block.getElseifNodes().stream().allMatch(node -> DomaTestExpressionEvaluator.isClosed(node.getExpression(), names));
    }

    private static boolean containsClause(SqlNode node) {
        for (SqlNode child : node.getChildren()) {
            // Subquery clauses belong to the predicate, not to the containing result projection.
            if (child instanceof ParensNode) continue;
            if (child instanceof ClauseNode || containsClause(child)) return true;
        }
        return false;
    }

    @Override public Void visitIfBlockNode(IfBlockNode node, Context context) {
        if (loopCondition(node, testEvaluator.loopFlags())) return super.visitIfBlockNode(node, context);
        Choice key = branchKeys.get(node);
        int choice = key == null ? 0 : choices.get(key);
        if (choice == 0) node.getIfNode().accept(this, context);
        else if (choice <= node.getElseifNodes().size()) node.getElseifNodes().get(choice - 1).accept(this, context);
        else if (node.getElseNode() != null) node.getElseNode().accept(this, context);
        node.getEndNode().accept(this, context);
        return null;
    }

    @Override public Void visitForBlockNode(ForBlockNode node, Context context) {
        Choice key = branchKeys.get(node);
        int iterations = key == null ? 2 : choices.get(key) + 1;
        testEvaluator.beginLoop(node.getForNode().getIdentifier(), iterations);
        try { return super.visitForBlockNode(node, context); }
        finally { testEvaluator.endLoop(); }
    }

    @Override public Void visitBindVariableNode(BindVariableNode node, Context context) {
        throw new GenerationException("Unexpected bind expression in metadata SQL; application expressions must not be evaluated.");
    }
}
