/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class JdbcMetadataReader {
    /** Does not execute the query and does not close the caller's connection. */
    public List<ResultColumn> read(Connection connection, String sql) throws SQLException {
        return read(connection, sql, "", TypeNameMappings.empty());
    }

    public List<ResultColumn> read(Connection connection, String sql, String qualifiedClassName,
            TypeNameMappings mappings) throws SQLException {
        return read(connection, sql, qualifiedClassName, mappings, stage -> {});
    }

    public List<ResultColumn> read(Connection connection, String sql, String qualifiedClassName,
            TypeNameMappings mappings, Consumer<JdbcErrorDetails.Stage> progress) throws SQLException {
        return read(connection, sql, qualifiedClassName, mappings, progress, SqlInput.Dialect.STANDARD);
    }

    public List<ResultColumn> read(Connection connection, String sql, String qualifiedClassName,
            TypeNameMappings mappings, Consumer<JdbcErrorDetails.Stage> progress, SqlInput.Dialect dialect) throws SQLException {
        String prepared = SqlInput.validate(sql, dialect);
        progress.accept(JdbcErrorDetails.Stage.PREPARING_SQL);
        try (PreparedStatement statement = connection.prepareStatement(prepared)) {
            progress.accept(JdbcErrorDetails.Stage.READING_COLUMNS);
            ResultSetMetaData metadata = statement.getMetaData();
            if (metadata == null) {
                throw new MetadataException("This driver cannot describe a SELECT before execution. "
                        + "Try a newer JDBC driver; query execution fallback is not supported.");
            }
            List<String> labels = new ArrayList<>();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                String label = metadata.getColumnLabel(i);
                if (label == null || label.isBlank()) label = metadata.getColumnName(i);
                labels.add(label);
            }
            List<String> fields = JavaNames.fieldNames(labels);
            List<ResultColumn> columns = new ArrayList<>();
            for (int i = 1; i <= labels.size(); i++) {
                String label = labels.get(i - 1);
                var override = mappings.resolve(qualifiedClassName, fields.get(i - 1));
                String type = override.isPresent() ? override.get()
                        : javaType(metadata.getColumnType(i), label, metadata.getColumnTypeName(i));
                columns.add(new ResultColumn(label, type));
            }
            progress.accept(JdbcErrorDetails.Stage.CLOSING_STATEMENT);
            return List.copyOf(columns);
        }
    }

    private static String javaType(int jdbcType, String label, String databaseType) throws SQLException {
        return switch (jdbcType) {
            case Types.BOOLEAN, Types.BIT -> "java.lang.Boolean";
            // Like the official generator, use Short to also represent unsigned TINYINT.
            case Types.TINYINT -> "java.lang.Short";
            case Types.SMALLINT -> "java.lang.Short";
            case Types.INTEGER -> "java.lang.Integer";
            case Types.BIGINT -> "java.lang.Long";
            case Types.REAL -> "java.lang.Float";
            case Types.FLOAT, Types.DOUBLE -> "java.lang.Double";
            case Types.NUMERIC, Types.DECIMAL -> "java.math.BigDecimal";
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR,
                    Types.NVARCHAR, Types.LONGNVARCHAR -> "java.lang.String";
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> "byte[]";
            case Types.DATE -> "java.time.LocalDate";
            case Types.TIME -> "java.time.LocalTime";
            case Types.TIMESTAMP -> "java.time.LocalDateTime";
            case Types.BLOB -> "java.sql.Blob";
            case Types.CLOB -> "java.sql.Clob";
            case Types.NCLOB -> "java.sql.NClob";
            case Types.SQLXML -> "java.sql.SQLXML";
            default -> throw new MetadataException("Unsupported type for column '" + label + "': "
                    + databaseType + " (JDBC " + jdbcType + "). Use an explicit CAST to a supported JDBC type.");
        };
    }
}
