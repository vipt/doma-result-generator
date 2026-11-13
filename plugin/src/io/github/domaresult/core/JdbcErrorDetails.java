/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Extracts diagnostic codes without exposing raw driver messages or connection details. */
public final class JdbcErrorDetails {
    public enum Stage {
        LOADING_DRIVER("loading the JDBC driver", "Check driver JARs, the driver class and Java compatibility."),
        CONNECTING("connecting to the database", "Check the JDBC URL, credentials and database service."),
        PREPARING_SQL("preparing the SELECT", "Check Metadata SQL and the current schema and privileges of this JDBC session."),
        READING_COLUMNS("reading SELECT column metadata", "Compare the JDBC driver and session settings with your SQL client; the driver failed while describing columns."),
        CLOSING_STATEMENT("closing the JDBC statement", "The driver failed while closing its statement."),
        CLOSING_CONNECTION("closing the JDBC connection", "The driver failed while closing its connection."),
        CLOSING_DRIVER("unloading the JDBC driver", "Check driver JAR dependencies and Java compatibility."),
        GENERATING_CLASS("generating the result class", "Check the result types and project Java build path."),
        MERGING_CLASS("merging the existing result class", "Check the saved class, imports and project Java build path."),
        VALIDATING_CLASS("validating the proposed result class", "Check the existing Java file and project Java build path; refresh Preview before updating.");

        private final String label, hint;
        Stage(String label, String hint) { this.label = label; this.hint = hint; }
    }

    public record Info(String summary, String details) {}
    private static final Pattern ORACLE_CODE = Pattern.compile("\\bORA-\\d{5}\\b", Pattern.CASE_INSENSITIVE);

    private JdbcErrorDetails() {}

    public static Info describe(Throwable failure, Stage stage, boolean oracleDriver) {
        Set<String> states = new LinkedHashSet<>();
        Set<String> vendorCodes = new LinkedHashSet<>();
        Set<String> oracleCodes = new LinkedHashSet<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty() && seen.size() < 16) {
            Throwable item = pending.removeFirst();
            if (!seen.add(item)) continue;
            if (item instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.matches("[A-Za-z0-9]{5}")) states.add(state);
                int code = sql.getErrorCode();
                if (code != 0) vendorCodes.add(Integer.toString(code));
                if (oracleDriver && code > 0 && code <= 99999) oracleCodes.add(String.format(Locale.ROOT, "ORA-%05d", code));
                if (sql.getNextException() != null) pending.addLast(sql.getNextException());
                // Only the fixed ORA number is extracted; never copy the raw message.
                if (sql.getMessage() != null) {
                    var matcher = ORACLE_CODE.matcher(sql.getMessage());
                    while (matcher.find() && oracleCodes.size() < 8) oracleCodes.add(matcher.group().toUpperCase(Locale.ROOT));
                }
            }
            if (item.getCause() != null) pending.addLast(item.getCause());
        }
        String type = failure.getClass().getSimpleName();
        String codes = (oracleCodes.isEmpty() ? "" : String.join(", ", oracleCodes) + ". ")
                + (vendorCodes.isEmpty() ? "" : "Vendor code: " + String.join(", ", vendorCodes) + ". ")
                + (states.isEmpty() ? "" : "SQLState: " + String.join(", ", states) + ". ");
        String summary = "Failed while " + stage.label + " (" + type + "). " + codes + "See Error details.";
        String details = "Stage: " + stage.label + "\nError type: " + type + "\n"
                + (oracleCodes.isEmpty() ? "" : "Oracle: " + String.join(", ", oracleCodes) + "\n")
                + (vendorCodes.isEmpty() ? "" : "Vendor code: " + String.join(", ", vendorCodes) + "\n")
                + (states.isEmpty() ? "" : "SQLState: " + String.join(", ", states) + "\n")
                + "\n" + stage.hint;
        if (!oracleCodes.isEmpty()) details += "\n\nOracle reference: https://docs.oracle.com/error-help/db/"
                + oracleCodes.iterator().next().toLowerCase(Locale.ROOT) + "/";
        return new Info(summary, details);
    }
}
