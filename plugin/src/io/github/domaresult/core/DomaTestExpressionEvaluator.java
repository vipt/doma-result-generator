/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import org.seasar.doma.internal.expr.EvaluationResult;
import org.seasar.doma.internal.expr.ExpressionEvaluator;
import org.seasar.doma.internal.expr.ExpressionParser;
import org.seasar.doma.internal.expr.node.ArithmeticOperatorNode;
import org.seasar.doma.internal.expr.node.ComparisonOperatorNode;
import org.seasar.doma.internal.expr.node.ExpressionNode;
import org.seasar.doma.internal.expr.node.LiteralNode;
import org.seasar.doma.internal.expr.node.LogicalBinaryOperatorNode;
import org.seasar.doma.internal.expr.node.NotOperatorNode;
import org.seasar.doma.internal.expr.node.ParensNode;
import org.seasar.doma.internal.expr.node.VariableNode;

/** Supplies sample collections to Doma; only constants and Doma's loop flags may be evaluated. */
final class DomaTestExpressionEvaluator extends ExpressionEvaluator {
    private static final int MAX_SAMPLE_ELEMENTS = 256;
    private final Deque<String> loops = new ArrayDeque<>();
    private Integer pendingIterations;
    private int sampleElements;

    void beginLoop(String identifier, int iterations) {
        if ((sampleElements += iterations) > MAX_SAMPLE_ELEMENTS) {
            throw new GenerationException("Metadata loop expansion exceeds " + MAX_SAMPLE_ELEMENTS
                    + " sample elements. Use a concrete SELECT in Metadata SQL.");
        }
        loops.push(identifier);
        pendingIterations = iterations;
    }

    void endLoop() { loops.pop(); pendingIterations = null; }

    Set<String> loopFlags() {
        Set<String> names = new LinkedHashSet<>();
        for (String identifier : loops) {
            String next = identifier + "_has_next", index = identifier + "_index";
            if (variableValues.containsKey(next)) names.add(next);
            if (variableValues.containsKey(index)) names.add(index);
        }
        return names;
    }

    @Override public EvaluationResult evaluate(ExpressionNode node) {
        if (pendingIterations != null) {
            int count = pendingIterations;
            pendingIterations = null;
            // The actual element value is never inferred: bind/literal comments retain their SQL test values.
            return new EvaluationResult(Collections.nCopies(count, 0), Iterable.class);
        }
        if (!isClosed(node, loopFlags())) {
            throw new GenerationException("This expression needs runtime values. Use concrete SQL in Metadata SQL; application code is not evaluated.");
        }
        return super.evaluate(node);
    }

    static boolean isClosed(String expression, Set<String> names) {
        return isClosed(new ExpressionParser(expression).parse(), names);
    }

    private static boolean isClosed(ExpressionNode node, Set<String> names) {
        if (node instanceof LiteralNode) return true;
        if (node instanceof VariableNode variable) return names.contains(variable.getExpression());
        if (node instanceof ParensNode parens) return isClosed(parens.getNode(), names);
        if (node instanceof NotOperatorNode not) return isClosed(not.getNode(), names);
        if (node instanceof ComparisonOperatorNode comparison) {
            return isClosed(comparison.getLeftNode(), names) && isClosed(comparison.getRightNode(), names);
        }
        if (node instanceof LogicalBinaryOperatorNode logical) {
            return isClosed(logical.getLeftNode(), names) && isClosed(logical.getRightNode(), names);
        }
        if (node instanceof ArithmeticOperatorNode arithmetic) {
            return isClosed(arithmetic.getLeftNode(), names) && isClosed(arithmetic.getRightNode(), names);
        }
        return false; // No constructors, fields, methods or application functions are invoked.
    }
}
