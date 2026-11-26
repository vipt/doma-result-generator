/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import freemarker.core.ParseException;
import freemarker.template.Configuration;
import freemarker.template.Template;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.seasar.doma.extension.gen.Generator;

/** Effective table-generation defaults; generation and editable copies use the same sources. */
public final class DomaGenTemplates {
    private DomaGenTemplates() {}
    public static final List<String> NAMES = List.of("entity.ftl", "dao.ftl", "entityListener.ftl",
            "selectById.sql.ftl", "selectByIdAndVersion.sql.ftl", "lib.ftl");

    public static String source(String name) throws IOException {
        if (!NAMES.contains(name)) throw new IllegalArgumentException("Unknown standard template.");
        try (var stream = Generator.class.getResourceAsStream("template/" + name)) {
            if (stream == null) throw new IOException("The bundled doma-gen template is unavailable: " + name);
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return name.equals("entity.ftl") ? text.replace("<#if !useAccessor>public </#if>", "private ") : text;
        }
    }

    /** Parse with FreeMarker itself, using the compatibility level of official doma-gen. */
    public static void validate(String name, String source) throws IOException {
        try {
            new Template(name, new StringReader(source), new Configuration(Configuration.VERSION_2_3_0));
        } catch (ParseException e) {
            throw new GenerationException("FTL syntax error in " + name + " at line " + e.getLineNumber()
                    + ", column " + e.getColumnNumber() + ". Check the FreeMarker directives and expressions.");
        }
    }
}
