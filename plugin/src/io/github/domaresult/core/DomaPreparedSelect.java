/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.seasar.doma.internal.jdbc.command.PreparedSqlParameterBinder;
import org.seasar.doma.jdbc.Config;
import org.seasar.doma.jdbc.PreparedSql;
import org.seasar.doma.jdbc.Sql;
import org.seasar.doma.jdbc.query.AbstractQuery;

/** Native Doma SQL and parameters; preparing/describing never executes the query. */
public final class DomaPreparedSelect {
    private final PreparedSql prepared;
    private final Config config;
    private final String sql;

    DomaPreparedSelect(PreparedSql prepared, Config config, String sql) {
        this.prepared = prepared; this.config = config; this.sql = sql;
    }

    public String sql() { return sql; }
    public int parameterCount() { return prepared.getParameters().size(); }

    void bind(PreparedStatement statement) throws SQLException {
        AbstractQuery query = new AbstractQuery() {
            @Override public Sql<?> getSql() { return prepared; }
            @Override public void complete() {}
        };
        query.setConfig(config);
        new PreparedSqlParameterBinder(query).bind(statement, prepared.getParameters());
    }
}
