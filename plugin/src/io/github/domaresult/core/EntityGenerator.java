/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.util.List;

public final class EntityGenerator {
    public String generate(String packageName, String className, List<ResultColumn> columns) {
        JavaNames.validateTarget(packageName, className);
        if (columns.isEmpty()) throw new GenerationException("The SELECT has no result columns.");
        List<String> fields = JavaNames.fieldNames(columns.stream().map(ResultColumn::label).toList());
        JavaImports imports = new JavaImports(packageName, className);
        String entity = imports.use("org.seasar.doma.Entity");
        String columnAnnotation = imports.use("org.seasar.doma.Column");
        List<String> types = columns.stream().map(column -> imports.use(column.javaType())).toList();
        StringBuilder out = new StringBuilder("package ").append(packageName).append(";\n\n");
        for (String type : imports.imports()) out.append("import ").append(type).append(";\n");
        out.append("\n/** Generated from SELECT result metadata. */\n")
                .append('@').append(entity).append("\npublic class ").append(className).append(" {\n");
        for (int i = 0; i < columns.size(); i++) {
            ResultColumn column = columns.get(i);
            out.append("\n    /** @domaGenerated */\n    @").append(columnAnnotation).append("(name = \"").append(escape(column.label()))
                    .append("\")\n    private ").append(types.get(i)).append(' ').append(fields.get(i)).append(";\n");
        }
        for (int i = 0; i < columns.size(); i++) {
            String field = fields.get(i);
            String type = types.get(i);
            String capital = JavaNames.accessorSuffix(field);
            out.append("\n    /** @domaGenerated */\n    public ").append(type).append(" get").append(capital).append("() {\n")
                    .append("        return ").append(field).append(";\n    }\n")
                    .append("\n    /** @domaGenerated */\n    public void set").append(capital).append('(').append(type).append(' ')
                    .append(field).append(") {\n        this.").append(field).append(" = ")
                    .append(field).append(";\n    }\n");
        }
        return out.append("}\n").toString();
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32 || c == 127) out.append(String.format("\\%03o", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
