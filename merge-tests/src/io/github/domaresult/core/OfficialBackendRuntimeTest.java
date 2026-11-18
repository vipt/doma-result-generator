/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
import org.osgi.framework.FrameworkUtil;
import org.seasar.doma.extension.gen.Generator;
import org.seasar.doma.internal.jdbc.sql.SqlParser;

/** Verifies official libraries and templates load in a real Eclipse/OSGi runtime. */
public class OfficialBackendRuntimeTest {
    @Test public void officialGeneratorAndTemplateAreAvailableToThePlugin() throws Exception {
        assertEquals("io.github.domaresult.org.seasar.doma.doma-gen", FrameworkUtil.getBundle(Generator.class).getSymbolicName());
        try (var template = Generator.class.getResourceAsStream("/org/seasar/doma/extension/gen/template/entity.ftl")) { assertNotNull(template); }
        String source = new EntityGenerator().generate("example.result", "AmountResult", List.of(new ResultColumn("AMOUNT", "java.math.BigDecimal")));
        assertTrue(source.contains("private BigDecimal amount;"));
        assertTrue(source.contains("import org.seasar.doma.Column;"));
    }

    @Test public void officialDomaSqlAssemblyWorksInsideThePluginClassLoader() {
        assertEquals("io.github.domaresult.org.seasar.doma.doma-core", FrameworkUtil.getBundle(SqlParser.class).getSymbolicName());
        String sql = SqlInput.forMetadata("SELECT 1 AS id WHERE /*%if enabled*/ AND 1=1 /*%end*/", SqlInput.Dialect.SQLSERVER);
        assertFalse(sql.contains("/*%if"));
        assertFalse(sql.matches("(?s).*WHERE\\s+AND\\b.*"));
        assertTrue(sql.contains("1=1"));
    }

    @Test public void officialLibrariesWorkWhileTheJdbcDriverOwnsTheContextClassLoader() throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (var jdbcLoader = new java.net.URLClassLoader(new java.net.URL[0], ClassLoader.getPlatformClassLoader())) {
            thread.setContextClassLoader(jdbcLoader);
            String source = new EntityGenerator().generate("example.result", "DriverContextResult", List.of(new ResultColumn("ID", "java.lang.Long")));
            assertTrue(source.contains("private Long id;"));
            assertFalse(SqlInput.forMetadata("SELECT 1 AS id WHERE AND 1=1", SqlInput.Dialect.SQLSERVER).contains("WHERE AND"));
            assertSame(jdbcLoader, thread.getContextClassLoader());
        } finally { thread.setContextClassLoader(previous); }
    }
}
