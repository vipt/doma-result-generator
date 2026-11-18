/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.util.Map;
import java.util.function.Function;
import javax.sql.DataSource;
import org.seasar.doma.internal.jdbc.sql.NodePreparedSqlBuilder;
import org.seasar.doma.internal.jdbc.sql.SqlParser;
import org.seasar.doma.internal.jdbc.sql.node.BindVariableNode;
import org.seasar.doma.internal.jdbc.sql.node.IfBlockNode;
import org.seasar.doma.jdbc.Config;
import org.seasar.doma.jdbc.SqlKind;
import org.seasar.doma.jdbc.dialect.Dialect;
import org.seasar.doma.jdbc.dialect.StandardDialect;

/** Official Doma SQL assembly with test literals and enabled filters, never application evaluation. */
final class DomaTestSqlBuilder extends NodePreparedSqlBuilder {
    private static final Config CONFIG = new Config() {
        @Override public DataSource getDataSource() { throw new UnsupportedOperationException("SQL assembly does not connect to a database."); }
        @Override public Dialect getDialect() { return new StandardDialect(); }
    };

    private DomaTestSqlBuilder() { super(CONFIG, SqlKind.SELECT, null); }

    static String prepare(String maskedSql, Map<String, String> originals) {
        try {
            String sql = new DomaTestSqlBuilder().build(new SqlParser(maskedSql).parse(), Function.identity()).getRawSql();
            for (var entry : originals.entrySet()) {
                if (entry.getKey().startsWith("--")) sql = sql.replace(entry.getKey() + "\n", entry.getValue());
                sql = sql.replace(entry.getKey(), entry.getValue());
            }
            return sql;
        } catch (RuntimeException e) {
            throw new GenerationException("Doma could not assemble the metadata test SQL. Check directive nesting and test literals in Metadata SQL.");
        }
    }

    @Override public Void visitIfBlockNode(IfBlockNode node, Context context) {
        node.getIfNode().accept(this, context);
        node.getEndNode().accept(this, context);
        return null;
    }

    @Override public Void visitBindVariableNode(BindVariableNode node, Context context) {
        throw new GenerationException("Unexpected bind expression in metadata SQL; application expressions must not be evaluated.");
    }
}
