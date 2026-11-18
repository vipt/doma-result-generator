/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import freemarker.cache.StringTemplateLoader;
import freemarker.template.TemplateExceptionHandler;
import java.io.StringWriter;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.seasar.doma.extension.gen.ColumnMeta;
import org.seasar.doma.extension.gen.EntityDesc;
import org.seasar.doma.extension.gen.EntityDescFactory;
import org.seasar.doma.extension.gen.EntityPropertyClassNameResolver;
import org.seasar.doma.extension.gen.EntityPropertyDesc;
import org.seasar.doma.extension.gen.EntityPropertyDescFactory;
import org.seasar.doma.extension.gen.Generator;
import org.seasar.doma.extension.gen.NamingType;
import org.seasar.doma.extension.gen.TableMeta;
import org.seasar.doma.extension.gen.dialect.StandardGenDialect;

/** Adapts doma-gen's entity factory and template generator to Eclipse preview/merge requirements. */
public final class EntityGenerator {
    private static final String TEMPLATE = """
            package ${entity.packageName};

            <#list imports as name>
            import ${name};
            </#list>

            /** Generated from SELECT result metadata. */
            @${entityAnnotation}
            public class ${entity.simpleName} {
            <#list properties as item>

                /** @domaGenerated */
                @${columnAnnotation}(name = "${item.columnLiteral}")
                private ${item.type} ${item.property.name};
            </#list>
            <#list properties as item>

                /** @domaGenerated */
                public ${item.type} get${item.accessorSuffix}() {
                    return ${item.property.name};
                }

                /** @domaGenerated */
                public void set${item.accessorSuffix}(${item.type} ${item.property.name}) {
                    this.${item.property.name} = ${item.property.name};
                }
            </#list>
            }
            """;

    public String generate(String packageName, String className, List<ResultColumn> columns) {
        return generate(packageName, className, columns, name -> false);
    }

    public String generate(String packageName, String className, List<ResultColumn> columns, Predicate<String> typeLookup) {
        JavaNames.validateTarget(packageName, className);
        if (columns.isEmpty()) throw new GenerationException("The SELECT has no result columns.");
        List<String> fields = JavaNames.fieldNames(columns.stream().map(ResultColumn::label).toList());
        Map<String, Integer> indexes = new HashMap<>();
        TableMeta table = new TableMeta();
        table.setName(className);
        for (int i = 0; i < columns.size(); i++) {
            indexes.put(columns.get(i).label(), i);
            ColumnMeta column = new ColumnMeta();
            column.setName(columns.get(i).label());
            column.setSqlType(Types.OTHER);
            table.addColumnMeta(column);
        }
        var propertyFactory = new EntityPropertyDescFactory(new StandardGenDialect(),
                new EntityPropertyClassNameResolver(null), "(?!)", null, null, null, true) {
            @Override protected void handleName(EntityDesc entity, EntityPropertyDesc property, ColumnMeta column) {
                property.setName(fields.get(indexes.get(column.getName())));
            }
            @Override protected void handlePropertyClassName(EntityDesc entity, EntityPropertyDesc property, ColumnMeta column) {
                property.setPropertyClassName(columns.get(indexes.get(column.getName())).javaType());
            }
        };
        EntityDesc entity = new EntityDescFactory(packageName, null, propertyFactory, NamingType.NONE,
                null, false, false, false, false, true, false).createEntityDesc(table, "", "", className);
        JavaImports imports = new JavaImports(packageName, className);
        for (ResultColumn column : columns) {
            String type = column.javaType();
            if (type.startsWith("java.lang.") && typeLookup.test(packageName + "." + type.substring("java.lang.".length()))) {
                imports.block(type.substring("java.lang.".length()));
            }
        }
        String entityAnnotation = imports.use("org.seasar.doma.Entity");
        String columnAnnotation = imports.use("org.seasar.doma.Column");
        List<Map<String, Object>> properties = new ArrayList<>();
        for (EntityPropertyDesc property : entity.getOwnEntityPropertyDescs()) {
            properties.add(Map.of("property", property, "type", imports.use(property.getPropertyClassName()),
                    "columnLiteral", escape(property.getColumnName()), "accessorSuffix", JavaNames.accessorSuffix(property.getName())));
        }
        return new PreviewGenerator().render(Map.of("entity", entity, "properties", properties, "imports", imports.imports(),
                "entityAnnotation", entityAnnotation, "columnAnnotation", columnAnnotation));
    }

    /** Official template processing in memory; the workspace updater owns all file writes. */
    private static final class PreviewGenerator extends Generator {
        PreviewGenerator() {
            super("UTF-8", null);
            StringTemplateLoader templates = new StringTemplateLoader();
            templates.putTemplate("result-entity.ftl", TEMPLATE);
            configuration.setTemplateLoader(templates);
            configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
            configuration.setLogTemplateExceptions(false);
        }
        String render(Map<String, Object> model) {
            StringWriter output = new StringWriter();
            try { process(getTemplate("result-entity.ftl"), model, output); }
            catch (RuntimeException e) { throw new GenerationException("Official doma-gen could not render the result class. Check property names and types."); }
            return output.toString();
        }
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (Character.isISOControl(c)) escaped.append(String.format("\\u%04x", (int) c));
                    else escaped.append(c);
                }
            }
        }
        return escaped.toString();
    }
}
