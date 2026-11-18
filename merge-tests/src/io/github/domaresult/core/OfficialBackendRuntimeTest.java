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
        String sql = SqlInput.forMetadata("SELECT 1 AS id WHERE /*%if true*/ AND 1=1 /*%end*/", SqlInput.Dialect.SQLSERVER);
        assertFalse(sql.contains("/*%if"));
        assertFalse(sql.matches("(?s).*WHERE\\s+AND\\b.*"));
        assertTrue(sql.contains("1=1"));
    }

    @Test public void standardEvaluatorUsesTypedArgumentsInsideThePluginClassLoader() {
        var values = TemplateArguments.parse("{\"enabled\":{\"type\":\"boolean\",\"value\":false}}", getClass().getClassLoader());
        var prepared = SqlInput.prepare("SELECT /*%if enabled*/ 1 /*%else*/ 2 /*%end*/ AS id", SqlInput.Dialect.SQLSERVER,
                values, getClass().getClassLoader(), "");
        assertTrue(prepared.sql().contains("2"));
        assertFalse(prepared.sql().contains("1"));
    }

    @Test public void expressionClasspathIncludesProjectOutputAndAdditionalFolders() throws Exception {
        var root = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot();
        var project = root.getProject("argument-classpath-" + java.util.UUID.randomUUID());
        try {
            project.create(null); project.open(null);
            var description = project.getDescription();
            description.setNatureIds(new String[] {org.eclipse.jdt.core.JavaCore.NATURE_ID});
            project.setDescription(description, null);
            var output = project.getFolder("bin"); output.create(true, true, null);
            var source = project.getFolder("src"); source.create(true, true, null);
            var javaProject = org.eclipse.jdt.core.JavaCore.create(project);
            javaProject.setRawClasspath(new org.eclipse.jdt.core.IClasspathEntry[] {
                    org.eclipse.jdt.core.JavaCore.newSourceEntry(source.getFullPath())}, output.getFullPath(), null);
            String extra = java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toString();
            try (var loader = io.github.domaresult.eclipse.ExpressionClasspath.open(javaProject, true, extra)) {
                var urls = java.util.Arrays.asList(loader.getURLs());
                assertTrue("Resolved classpath: " + urls, urls.contains(output.getLocation().toFile().toURI().toURL()));
                assertTrue(urls.contains(java.nio.file.Path.of(extra).toUri().toURL()));
            }
        } finally { if (project.exists()) project.delete(true, true, null); }
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
