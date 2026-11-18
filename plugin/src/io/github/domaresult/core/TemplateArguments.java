/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.jdt.core.dom.*;
import org.seasar.doma.internal.expr.Value;

/** Converts explicitly typed user arguments; it does not interpret SQL or select branches. */
public final class TemplateArguments {
    private TemplateArguments() {}

    public static Map<String, Value> parse(String json, ClassLoader loader) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(loader);
            Gson gson = new GsonBuilder().setStrictness(Strictness.STRICT)
                    .registerTypeAdapter(LocalDate.class, (JsonDeserializer<LocalDate>)(j,t,c) -> LocalDate.parse(j.getAsString()))
                    .registerTypeAdapter(LocalTime.class, (JsonDeserializer<LocalTime>)(j,t,c) -> LocalTime.parse(j.getAsString()))
                    .registerTypeAdapter(LocalDateTime.class, (JsonDeserializer<LocalDateTime>)(j,t,c) -> LocalDateTime.parse(j.getAsString()))
                    .registerTypeAdapter(java.sql.Date.class, (JsonDeserializer<java.sql.Date>)(j,t,c) -> java.sql.Date.valueOf(j.getAsString()))
                    .registerTypeAdapter(java.sql.Time.class, (JsonDeserializer<java.sql.Time>)(j,t,c) -> java.sql.Time.valueOf(j.getAsString()))
                    .registerTypeAdapter(java.sql.Timestamp.class, (JsonDeserializer<java.sql.Timestamp>)(j,t,c) -> java.sql.Timestamp.valueOf(j.getAsString()))
                    .create();
            JsonElement document = gson.fromJson(json.isBlank() ? "{}" : json, JsonElement.class);
            if (document == null || !document.isJsonObject()) throw new IllegalArgumentException();
            Map<String, Value> arguments = new LinkedHashMap<>();
            for (var entry : document.getAsJsonObject().entrySet()) {
                if (!JavaNames.isIdentifier(entry.getKey())) throw new IllegalArgumentException();
                JsonObject specification = entry.getValue().getAsJsonObject();
                if (specification.size() != 2 || !specification.has("type") || !specification.has("value")) throw new IllegalArgumentException();
                Type type = javaType(specification.get("type").getAsString(), loader);
                Class<?> raw = TypeToken.get(type).getRawType();
                JsonElement value = specification.get("value");
                if (raw.isPrimitive() && value.isJsonNull()) throw new IllegalArgumentException();
                arguments.put(entry.getKey(), new Value(raw, gson.fromJson(value, type)));
            }
            return Map.copyOf(arguments);
        } catch (Exception | LinkageError e) {
            throw new GenerationException("Cannot read Arguments JSON. Each argument needs a valid name, Java type and JSON value. "
                    + "Use fully qualified types; build the project before loading its classes. Argument values are not logged.");
        } finally { thread.setContextClassLoader(previous); }
    }

    @SuppressWarnings("deprecation")
    private static Type javaType(String source, ClassLoader loader) throws ClassNotFoundException {
        ASTParser parser = ASTParser.newParser(AST.JLS17);
        parser.setCompilerOptions(Map.of("org.eclipse.jdt.core.compiler.source", "17",
                "org.eclipse.jdt.core.compiler.compliance", "17", "org.eclipse.jdt.core.compiler.codegen.targetPlatform", "17"));
        parser.setSource(("class __Arguments { " + source + " value; }").toCharArray());
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        if (unit.getProblems().length != 0 || unit.types().size() != 1) throw new IllegalArgumentException();
        TypeDeclaration declaration = (TypeDeclaration) unit.types().get(0);
        if (declaration.bodyDeclarations().size() != 1 || declaration.getFields().length != 1) throw new IllegalArgumentException();
        FieldDeclaration field = declaration.getFields()[0];
        if (field.fragments().size() != 1 || ((VariableDeclarationFragment) field.fragments().get(0)).getInitializer() != null) throw new IllegalArgumentException();
        return resolve(field.getType(), loader);
    }

    private static Type resolve(org.eclipse.jdt.core.dom.Type node, ClassLoader loader) throws ClassNotFoundException {
        if (node instanceof PrimitiveType) return switch (node.toString()) {
            case "boolean" -> boolean.class; case "byte" -> byte.class; case "short" -> short.class;
            case "int" -> int.class; case "long" -> long.class; case "float" -> float.class;
            case "double" -> double.class; case "char" -> char.class; default -> throw new IllegalArgumentException();
        };
        if (node instanceof ParameterizedType parameterized) {
            Type[] arguments = new Type[parameterized.typeArguments().size()];
            for (int i = 0; i < arguments.length; i++) arguments[i] = resolve((org.eclipse.jdt.core.dom.Type) parameterized.typeArguments().get(i), loader);
            return TypeToken.getParameterized((Class<?>) resolve(parameterized.getType(), loader), arguments).getType();
        }
        if (node instanceof ArrayType array) {
            Type type = resolve(array.getElementType(), loader);
            for (int i = 0; i < array.getDimensions(); i++) type = TypeToken.getArray(type).getType();
            return type;
        }
        if (!(node instanceof SimpleType || node instanceof QualifiedType || node instanceof NameQualifiedType)) throw new IllegalArgumentException();
        String name = node.toString();
        if (!name.contains(".")) name = "java.lang." + name;
        return Class.forName(name, false, loader);
    }
}
