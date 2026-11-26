/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import freemarker.cache.FileTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.StringTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.template.TemplateExceptionHandler;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.seasar.doma.extension.gen.*;
import org.seasar.doma.extension.gen.dialect.GenDialect;
import org.seasar.doma.extension.gen.dialect.GenDialectRegistry;
import org.seasar.doma.extension.gen.internal.util.JdbcUtil;

/** The table pipeline from doma-gen's Gen task, adapted to an in-memory preview. */
public final class TableGenerator {
    public record Table(String catalog, String schema, String name) {
        public String label() {
            return String.join(".", java.util.stream.Stream.of(catalog, schema, name)
                    .filter(s -> s != null && !s.isEmpty()).toList());
        }
        static Table of(TableMeta meta) { return new Table(meta.getCatalogName(), meta.getSchemaName(), meta.getName()); }
    }
    public enum Kind { ENTITY, DAO, LISTENER, SQL }
    public record Artifact(Kind kind, String path, String source) {
        public Artifact {
            Path relative = Path.of(path);
            if (relative.isAbsolute() || relative.startsWith("..") || !relative.normalize().equals(relative)
                    || path.contains("\\") || path.isBlank()) throw new GenerationException("Invalid generated file path.");
        }
    }
    public record Options(String entityPackage, String daoPackage, boolean listener,
            boolean qualifySchema, Path mappings, Path templates) {
        public Options {
            JavaNames.validateTarget(entityPackage, "GeneratedEntity");
            JavaNames.validateTarget(daoPackage, "GeneratedDao");
            if (mappings != null && !Files.isRegularFile(mappings)) throw new GenerationException("Select an existing name.properties file.");
            if (templates != null && !Files.isDirectory(templates)) throw new GenerationException("Select an existing doma-gen template directory.");
        }
    }

    public List<Table> tables(Connection connection, String url, String schema, boolean views,
            PreviewExecution.Cancellation cancel) throws SQLException {
        try {
            return new Reader(connection, dialect(url), schema, views, null, cancel).names();
        } catch (GenException e) { throw failure(e); }
    }

    public List<Artifact> generate(Connection connection, String url, String schema, boolean views,
            Set<Table> selected, Options options, PreviewExecution.Cancellation cancel) throws SQLException {
        if (selected.isEmpty()) throw new GenerationException("Select at least one table.");
        try {
            GenDialect dialect = dialect(url);
            var tables = new Reader(connection, dialect, schema, views, selected, cancel).read();
            if (!tables.stream().map(Table::of).collect(java.util.stream.Collectors.toSet()).equals(selected)) {
                throw new GenerationException("The selected tables changed or are no longer available. Load tables again.");
            }
            var properties = new EntityPropertyDescFactory(dialect,
                    new EntityPropertyClassNameResolver(options.mappings() == null ? null : options.mappings().toFile()),
                    "VERSION([_]?NO)?", null, null, null, true);
            var entities = new EntityDescFactory(options.entityPackage(), null, properties, NamingType.NONE,
                    null, false, options.qualifySchema(), true, true, true, options.listener());
            var daos = new DaoDescFactory(options.daoPackage(), "Dao", null);
            var listeners = new EntityListenerDescFactory(options.entityPackage(), null);
            var sqls = new SqlDescFactory(options.templates() == null ? null : options.templates().toFile(), dialect);
            var generator = new MemoryGenerator(options.templates());
            List<Artifact> output = new ArrayList<>();
            for (TableMeta table : tables) {
                cancel.check();
                EntityDesc entity = entities.createEntityDesc(table, "", "");
                JavaNames.validateTarget(entity.getPackageName(), entity.getSimpleName());
                output.add(java(Kind.ENTITY, entity.getQualifiedName(), generator.render(entity.getTemplateName(), entity)));
                if (options.listener()) {
                    var listener = listeners.createEntityListenerDesc(entity);
                    output.add(java(Kind.LISTENER, listener.getQualifiedName(), generator.render(listener.getTemplateName(), listener)));
                }
                DaoDesc dao = daos.createDaoDesc(entity);
                output.add(java(Kind.DAO, dao.getQualifiedName(), generator.render(dao.getTemplateName(), dao)));
                for (SqlDesc sql : sqls.createSqlDescs(entity)) {
                    output.add(new Artifact(Kind.SQL, "META-INF/" + dao.getQualifiedName().replace('.', '/') + "/" + sql.getFileName(),
                            generator.render(sql.getTemplateName(), sql)));
                }
            }
            Set<String> destinations = new HashSet<>();
            for (Artifact artifact : output) {
                cancel.check();
                if (!destinations.add(artifact.path().toLowerCase(java.util.Locale.ROOT))) throw new GenerationException(
                        "Selected tables generate the same file: " + artifact.path() + ". Select one schema at a time or change the destination packages.");
            }
            return List.copyOf(output);
        } catch (GenException e) { cancel.check(); throw failure(e); }
    }

    private static Artifact java(Kind kind, String qualifiedName, String source) {
        return new Artifact(kind, qualifiedName.replace('.', '/') + ".java", source);
    }
    private static SQLException failure(GenException error) {
        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql;
            if (cause instanceof freemarker.core.ParseException syntax) {
                throw new GenerationException("FTL syntax error in " + syntax.getTemplateName() + " at line " + syntax.getLineNumber()
                        + ", column " + syntax.getColumnNumber() + ". Open Edit templates and check the FreeMarker syntax.");
            }
            if (cause instanceof freemarker.template.TemplateException template) {
                throw new GenerationException("FTL processing failed in " + template.getTemplateSourceName() + " at line " + template.getLineNumber()
                        + ". Open Edit templates and check the data-model property or expression.");
            }
        }
        throw new GenerationException("Official doma-gen could not generate the selected tables. Check column types, name.properties and templates.");
    }
    private static GenDialect dialect(String url) {
        String name = JdbcUtil.inferDialectName(url);
        if (url.toLowerCase(java.util.Locale.ROOT).startsWith("jdbc:mariadb:")) name = "mysql";
        GenDialect dialect = name == null ? null : GenDialectRegistry.lookup(name);
        if (dialect == null) throw new GenerationException("doma-gen does not recognize this JDBC URL's database dialect.");
        return dialect;
    }

    private static final class Reader extends TableMetaReader {
        private final Connection connection;
        private final Set<Table> selected;
        private final PreviewExecution.Cancellation cancel;
        Reader(Connection connection, GenDialect dialect, String schema, boolean views, Set<Table> selected,
                PreviewExecution.Cancellation cancel) {
            super(dialect, borrowed(connection, cancel), schema == null || schema.isBlank() ? null : schema,
                    ".*", "(?!)", views ? List.of("TABLE", "VIEW", "BASE TABLE") : List.of("TABLE", "BASE TABLE"));
            this.connection = connection;
            this.selected = selected == null ? null : Set.copyOf(selected);
            this.cancel = cancel;
        }
        List<Table> names() throws SQLException {
            cancel.check();
            var metadata = connection.getMetaData();
            var result = getTableMetas(metadata, schemaName == null ? getDefaultSchemaName(metadata) : schemaName);
            cancel.check();
            return result.stream().map(Table::of).sorted(java.util.Comparator.comparing(Table::label)).toList();
        }
        @Override protected boolean isTargetTable(TableMeta table) {
            cancel.check();
            return selected == null || selected.contains(Table.of(table));
        }
    }

    private static DataSource borrowed(Connection connection, PreviewExecution.Cancellation cancel) {
        Connection borrowed = (Connection) Proxy.newProxyInstance(TableGenerator.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, call, args) -> {
            if (call.getName().equals("close")) return null;
            cancel.check();
            try { return call.invoke(connection, args); }
            catch (InvocationTargetException e) { throw e.getCause(); }
        });
        return (DataSource) Proxy.newProxyInstance(TableGenerator.class.getClassLoader(), new Class<?>[]{DataSource.class}, (p, call, args) -> {
            if (call.getName().equals("getConnection") && (args == null || args.length == 0)) return borrowed;
            throw new SQLFeatureNotSupportedException("Only the caller-owned connection is available.");
        });
    }

    private static final class MemoryGenerator extends Generator {
        MemoryGenerator(Path templates) {
            super("UTF-8", templates == null ? null : templates.toFile());
            // Keep the official model and template, changing only default field visibility.
            // An explicitly supplied entity.ftl takes precedence over this default.
            try {
                StringTemplateLoader defaults = new StringTemplateLoader();
                defaults.putTemplate("entity.ftl", DomaGenTemplates.source("entity.ftl"));
                List<TemplateLoader> loaders = new ArrayList<>();
                if (templates != null) loaders.add(new FileTemplateLoader(templates.toFile()));
                loaders.add(defaults);
                loaders.add(configuration.getTemplateLoader());
                configuration.setTemplateLoader(new MultiTemplateLoader(loaders.toArray(TemplateLoader[]::new)));
                configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
                configuration.setLogTemplateExceptions(false);
            } catch (IOException e) { throw new GenerationException("Cannot load official doma-gen templates."); }
        }
        String render(String template, Object model) {
            StringWriter output = new StringWriter();
            process(getTemplate(template), model, output);
            return output.toString();
        }
    }
}
