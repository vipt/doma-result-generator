/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JdbcErrorDetailsTest {
    @Test void reportsOracleVendorNumberAndPhaseEvenWhenOnlySqlStateWasPreviouslyVisible() {
        var error = new SQLException("private driver message with password=do-not-display", "72000", 17023);
        var info = JdbcErrorDetails.describe(error, JdbcErrorDetails.Stage.READING_COLUMNS, true);
        assertTrue(info.summary().contains("ORA-17023"));
        assertTrue(info.summary().contains("Vendor code: 17023"));
        assertTrue(info.summary().contains("SQLState: 72000"));
        assertTrue(info.details().contains("reading SELECT column metadata"));
        assertFalse(info.details().contains("do-not-display"));
    }

    @Test void extractsCodesFromChainedExceptionsWithoutExposingRawSqlCredentialsOrUrls() {
        var error = new SQLException("SELECT PRIVATE_DATA FROM PRIVATE_TABLE; jdbc:oracle:thin:@private-host password=secret", "72000", 0);
        error.setNextException(new SQLException("ORA-00942: private schema details", "42000", 942));
        var info = JdbcErrorDetails.describe(error, JdbcErrorDetails.Stage.PREPARING_SQL, true);
        String displayed = info.summary() + info.details();
        assertTrue(displayed.contains("ORA-00942"));
        assertTrue(displayed.contains("942"));
        for (String privateText : new String[] { "PRIVATE_DATA", "PRIVATE_TABLE", "private-host", "password", "secret", "private schema details" }) {
            assertFalse(displayed.contains(privateText), privateText);
        }
    }

    @Test void nonOracleVendorCodesAreNotMislabelledAsOracle() {
        var info = JdbcErrorDetails.describe(new SQLException("private details", "42S02", 1146), JdbcErrorDetails.Stage.PREPARING_SQL, false);
        assertTrue(info.summary().contains("Vendor code: 1146"));
        assertTrue(info.summary().contains("SQLState: 42S02"));
        assertFalse(info.summary().contains("ORA-"));
    }

    @Test void detectsOracleNumberInMessageEvenIfTheVendorCodeIsZero() {
        var info = JdbcErrorDetails.describe(new SQLException("ORA-01017: private details", "72000", 0), JdbcErrorDetails.Stage.CONNECTING, false);
        assertTrue(info.summary().contains("ORA-01017"));
        assertFalse(info.details().contains("private details"));
    }

    @Test void handlesCyclesInCausesAndIgnoresMalformedSqlState() {
        var wrapper = new RuntimeException("private wrapper");
        var sql = new SQLException("ORA-00942: private cause", "secret state", 942);
        wrapper.initCause(sql);
        sql.initCause(wrapper);
        var info = JdbcErrorDetails.describe(wrapper, JdbcErrorDetails.Stage.PREPARING_SQL, true);
        assertTrue(info.summary().contains("ORA-00942"));
        assertFalse(info.details().contains("secret state"));
        assertFalse(info.details().contains("private"));
    }

    @Test void missingCodesAndNonSqlFailuresStillHaveAnActionablePhase() {
        var info = JdbcErrorDetails.describe(new ClassNotFoundException("private JAR path"), JdbcErrorDetails.Stage.LOADING_DRIVER, false);
        assertTrue(info.summary().contains("ClassNotFoundException"));
        assertTrue(info.summary().contains("loading the JDBC driver"));
        assertFalse(info.details().contains("private JAR path"));
    }

    @Test void preparingStatementFailureIsDistinguishedFromMetadataFailure() {
        var stage = new AtomicReference<>(JdbcErrorDetails.Stage.CONNECTING);
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class },
                (proxy, method, args) -> { throw new SQLException("ORA-00942: private table", "72000", 942); });
        var error = assertThrows(SQLException.class, () -> new JdbcMetadataReader().read(connection, "select * from employee",
                "example.Result", TypeNameMappings.empty(), stage::set));
        assertEquals(JdbcErrorDetails.Stage.PREPARING_SQL, stage.get());
        assertTrue(JdbcErrorDetails.describe(error, stage.get(), true).summary().contains("ORA-00942"));
    }

    @Test void metadataFailureKeepsItsOracleCodeAndDoesNotExecuteTheQuery() {
        var stage = new AtomicReference<>(JdbcErrorDetails.Stage.CONNECTING);
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { PreparedStatement.class },
                (proxy, method, args) -> {
                    if (method.getName().startsWith("execute")) fail("The query must not be executed");
                    if (method.getName().equals("getMetaData")) throw new SQLException("private message", "72000", 17023);
                    if (method.getName().equals("close")) return null;
                    throw new UnsupportedOperationException(method.getName());
                });
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement")) return statement;
                    throw new UnsupportedOperationException(method.getName());
                });
        var error = assertThrows(SQLException.class, () -> new JdbcMetadataReader().read(connection, "select * from employee",
                "example.Result", TypeNameMappings.empty(), stage::set));
        assertEquals(JdbcErrorDetails.Stage.READING_COLUMNS, stage.get());
        assertTrue(JdbcErrorDetails.describe(error, stage.get(), true).summary().contains("ORA-17023"));
    }
}
