/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.seasar.doma.internal.expr.ExpressionParser;
import org.seasar.doma.internal.expr.Value;
import org.seasar.doma.internal.expr.node.*;
import org.seasar.doma.internal.jdbc.sql.node.*;
import org.seasar.doma.jdbc.SqlNode;

/** Supplies sample objects only. Doma's AST is read, never changed or evaluated here. */
final class AutomaticArguments {
    private final Map<String, Hint> roots = new LinkedHashMap<>();
    private final PreviewExecution.Cancellation cancellation;
    private int remaining = 512;

    private static final class Hint {
        Class<?> type;
        Object sample;
        boolean bean, collection;
        Hint element;
        final Map<String, Hint> members = new LinkedHashMap<>();
        Hint member(String name) { return members.computeIfAbsent(name, ignored -> new Hint()); }
        Hint element() { collection = true; if (element == null) element = new Hint(); return element; }
        void suggest(Class<?> candidate, Object value) {
            if (candidate != null && candidate != void.class && candidate != Object.class) {
                type = candidate; sample = value;
            }
        }
    }

    private AutomaticArguments(PreviewExecution.Cancellation cancellation) { this.cancellation = cancellation; }

    static Map<String, Value> create(SqlNode tree, Function<Set<String>, Map<String, Type>> types, PreviewExecution.Cancellation cancellation) {
        var factory = new AutomaticArguments(cancellation);
        factory.sql(tree, new HashMap<>());
        Map<String, Type> declared = types.apply(Set.copyOf(factory.roots.keySet()));
        Map<String, Value> result = new LinkedHashMap<>();
        factory.roots.forEach((name, hint) -> {
            Type type = declared.get(name);
            if (type == null) {
                if (hint.bean) throw new GenerationException("Cannot determine the DTO type for automatic argument '" + name
                        + "'. Build the project and use the SQL file associated with its DAO method under META-INF.");
                type = hint.type != null ? hint.type : hint.collection ? List.class : String.class;
            }
            try {
                Object value = factory.value(type, hint, new HashSet<>(), 0);
                result.put(name, new Value(raw(type), value));
            } catch (ReflectiveOperationException | IllegalArgumentException | LinkageError failure) {
                throw new GenerationException("Cannot create automatic argument '" + name
                        + "'. Check the compiled DAO parameter type, its constructor and project dependencies. No argument input is required.");
            }
        });
        return result;
    }

    private void sql(SqlNode node, Map<String, Hint> scope) {
        cancellation.check();
        if (node instanceof ForBlockNode block) {
            ForNode loop = block.getForNode();
            Hint items = expression(loop.getExpression(), scope, null);
            Hint item = items == null ? new Hint() : items.element();
            Map<String, Hint> local = new HashMap<>(scope);
            local.put(loop.getIdentifier(), item);
            local.put(loop.getIdentifier() + "_has_next", new Hint());
            local.put(loop.getIdentifier() + "_index", new Hint());
            for (SqlNode child : loop.getChildren()) sql(child, local);
            return;
        }
        if (node instanceof ValueNode value) {
            Hint hint = expression(value.getVariableName(), scope, null);
            if (hint != null) {
                if (value.getWordNode() != null) literalHint(value.getWordNode().getWord(), hint);
                else if (value.getParensNode() != null) {
                    Hint element = hint.element();
                    for (SqlNode child : value.getParensNode().getChildren()) {
                        if (child instanceof WordNode word) { literalHint(word.getWord(), element); break; }
                    }
                }
            }
        } else if (node instanceof IfNode condition) expression(condition.getExpression(), scope, Boolean.class);
        else if (node instanceof ElseifNode condition) expression(condition.getExpression(), scope, Boolean.class);
        else if (node instanceof EmbeddedVariableNode embedded) expression(embedded.getVariableName(), scope, String.class);
        // %expand's alias is a Doma expression too; a constant alias needs no argument.
        else if (node instanceof ExpandNode expand) expression(expand.getAlias(), scope, String.class);
        for (SqlNode child : node.getChildren()) sql(child, scope);
    }

    private static void literalHint(String token, Hint hint) {
        // The official expression parser recognizes ordinary Doma/SQL test literals.
        // This supplies sample values; it never replaces the SQL test literal or a directive.
        try {
            ExpressionNode parsed = new ExpressionParser(token).parse();
            if (parsed instanceof LiteralNode literal) hint.suggest(literal.getValueClass(), literal.getValue());
        } catch (org.seasar.doma.DomaException ignored) {
            // A DB-specific test literal does not override the DAO's declared Java type.
        }
    }

    private Hint expression(String text, Map<String, Hint> scope, Class<?> expected) {
        if (text == null || text.isBlank()) return null;
        return expression(new ExpressionParser(text).parse(), scope, expected);
    }

    private Hint expression(ExpressionNode node, Map<String, Hint> scope, Class<?> expected) {
        cancellation.check();
        if (node == null) return null;
        if (node instanceof VariableNode variable) {
            String name = variable.getExpression();
            Hint hint = scope.containsKey(name) ? scope.get(name) : roots.computeIfAbsent(name, ignored -> new Hint());
            if (expected != null && hint.type == null) hint.type = expected;
            return hint;
        }
        if (node instanceof FieldOperatorNode field) {
            Hint owner = expression(field.getTargetObjectNode(), scope, null);
            if (owner == null) return null;
            owner.bean = true;
            Hint hint = owner.member(field.getFieldName());
            if (expected != null && hint.type == null) hint.type = expected;
            return hint;
        }
        if (node instanceof MethodOperatorNode method) {
            Hint owner = expression(method.getTargetObjectNode(), scope, null);
            expression(method.getParametersNode(), scope, null);
            if (owner == null) return null;
            String name = method.getMethodName();
            if (Set.of("size", "isEmpty", "iterator", "get").contains(name)) {
                owner.element();
                if (name.equals("get")) return owner.element();
                return null;
            }
            if (Set.of("length", "startsWith", "endsWith", "substring", "trim", "toLowerCase", "toUpperCase", "equals", "equalsIgnoreCase", "contains").contains(name)) {
                if (owner.type == null) owner.type = String.class;
                return null;
            }
            owner.bean = true;
            String property = name.startsWith("get") && name.length() > 3 ? decapitalize(name.substring(3))
                    : name.startsWith("is") && name.length() > 2 ? decapitalize(name.substring(2)) : name;
            Hint hint = owner.member(property);
            if (expected != null && hint.type == null) hint.type = expected;
            return hint;
        }
        if (node instanceof ComparisonOperatorNode comparison) {
            Class<?> left = literalType(comparison.getLeftNode()), right = literalType(comparison.getRightNode());
            expression(comparison.getLeftNode(), scope, right);
            expression(comparison.getRightNode(), scope, left);
        } else if (node instanceof ArithmeticOperatorNode arithmetic) {
            expression(arithmetic.getLeftNode(), scope, literalType(arithmetic.getRightNode()));
            expression(arithmetic.getRightNode(), scope, literalType(arithmetic.getLeftNode()));
        } else if (node instanceof LogicalBinaryOperatorNode logical) {
            expression(logical.getLeftNode(), scope, Boolean.class);
            expression(logical.getRightNode(), scope, Boolean.class);
        } else if (node instanceof NotOperatorNode not) expression(not.getNode(), scope, Boolean.class);
        else if (node instanceof org.seasar.doma.internal.expr.node.ParensNode parens) return expression(parens.getNode(), scope, expected);
        else if (node instanceof CommaOperatorNode comma) for (ExpressionNode child : comma.getNodes()) expression(child, scope, null);
        else if (node instanceof FunctionOperatorNode function) expression(function.getParametersNode(), scope, null);
        else if (node instanceof StaticMethodOperatorNode method) expression(method.getParametersNode(), scope, null);
        else if (node instanceof NewOperatorNode constructor) expression(constructor.getParametersNode(), scope, null);
        return null;
    }

    private static Class<?> literalType(ExpressionNode node) {
        return node instanceof LiteralNode literal && literal.getValue() != null ? literal.getValueClass() : null;
    }

    @SuppressWarnings("unchecked")
    private Object value(Type type, Hint hint, Set<Class<?>> path, int depth) throws ReflectiveOperationException {
        cancellation.check();
        if (--remaining < 0) throw new GenerationException("Automatic argument object graph is too large. Check the DAO parameter types.");
        Class<?> clazz = raw(type);
        if (clazz == Object.class) clazz = hint.collection ? List.class : hint.type == null ? String.class : hint.type;
        Object sample = hint.sample;
        if (clazz == String.class || clazz == CharSequence.class) return sample instanceof String ? sample : "";
        if (clazz == boolean.class || clazz == Boolean.class) return sample instanceof Boolean ? sample : false;
        if (clazz == char.class || clazz == Character.class) return '0';
        Number number = sample instanceof Number n ? n : 0;
        if (clazz == byte.class || clazz == Byte.class) return number.byteValue();
        if (clazz == short.class || clazz == Short.class) return number.shortValue();
        if (clazz == int.class || clazz == Integer.class) return number.intValue();
        if (clazz == long.class || clazz == Long.class) return number.longValue();
        if (clazz == float.class || clazz == Float.class) return number.floatValue();
        if (clazz == double.class || clazz == Double.class) return number.doubleValue();
        if (clazz == BigDecimal.class) return new BigDecimal(number.toString());
        if (clazz == BigInteger.class) return BigInteger.valueOf(number.longValue());
        if (clazz == LocalDate.class) return LocalDate.of(2000, 1, 1);
        if (clazz == LocalTime.class) return LocalTime.NOON;
        if (clazz == LocalDateTime.class) return LocalDateTime.of(2000, 1, 1, 12, 0);
        if (clazz == Instant.class) return Instant.EPOCH;
        if (clazz == OffsetDateTime.class) return OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);
        if (clazz == ZonedDateTime.class) return ZonedDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);
        if (clazz == java.sql.Date.class) return java.sql.Date.valueOf("2000-01-01");
        if (clazz == java.sql.Time.class) return java.sql.Time.valueOf("12:00:00");
        if (clazz == java.sql.Timestamp.class) return java.sql.Timestamp.valueOf("2000-01-01 12:00:00");
        if (clazz == java.util.Date.class) return new java.util.Date(0);
        if (clazz == UUID.class) return new UUID(0, 0);
        if (clazz == byte[].class) return new byte[0];
        if (clazz.isEnum()) return clazz.getEnumConstants().length == 0 ? null : clazz.getEnumConstants()[0];
        if (clazz.isArray() || type instanceof GenericArrayType) {
            Type elementType = type instanceof GenericArrayType array ? array.getGenericComponentType() : clazz.getComponentType();
            Object array = Array.newInstance(raw(elementType), 1);
            Array.set(array, 0, value(elementType, hint.element(), path, depth + 1));
            return array;
        }
        if (Optional.class.isAssignableFrom(clazz)) return Optional.ofNullable(value(argument(type, 0, hint.type), hint, path, depth + 1));
        if (Collection.class.isAssignableFrom(clazz) || clazz == Iterable.class) {
            Hint element = hint.element();
            Type elementType = argument(type, 0, element.type);
            Collection<Object> items;
            if (clazz.isInterface()) items = SortedSet.class.isAssignableFrom(clazz) ? new TreeSet<>()
                    : Set.class.isAssignableFrom(clazz) ? new LinkedHashSet<>()
                    : Queue.class.isAssignableFrom(clazz) ? new LinkedList<>() : new ArrayList<>();
            else items = (Collection<Object>) clazz.getDeclaredConstructor().newInstance();
            items.add(value(elementType, element, path, depth + 1));
            return items;
        }
        if (Map.class.isAssignableFrom(clazz)) return new LinkedHashMap<>();
        if (depth >= 5 || !path.add(clazz)) return null;
        try {
            if (clazz.isRecord()) {
                RecordComponent[] components = clazz.getRecordComponents();
                Class<?>[] types = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
                Object[] values = new Object[components.length];
                for (int i = 0; i < components.length; i++) values[i] = value(components[i].getGenericType(), hint.member(components[i].getName()), path, depth + 1);
                Constructor<?> constructor = clazz.getDeclaredConstructor(types);
                constructor.setAccessible(true);
                return constructor.newInstance(values);
            }
            Object bean;
            try {
                Constructor<?> constructor = clazz.getDeclaredConstructor();
                constructor.setAccessible(true);
                bean = constructor.newInstance();
            } catch (NoSuchMethodException noDefault) {
                for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                    if (constructor.getParameterCount() == 1 && isBasic(constructor.getParameterTypes()[0])) {
                        constructor.setAccessible(true);
                        return constructor.newInstance(value(constructor.getGenericParameterTypes()[0], hint, path, depth + 1));
                    }
                }
                throw noDefault;
            }
            for (Class<?> current = clazz; current != null && current != Object.class; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers()) || field.isSynthetic()) continue;
                    // Fill basic fields, containers and SQL-referenced nested DTOs, without following arbitrary object graphs.
                    if (!isBasic(field.getType()) && !field.getType().isArray()
                            && !Iterable.class.isAssignableFrom(field.getType()) && !Optional.class.isAssignableFrom(field.getType())
                            && !hint.members.containsKey(field.getName())) continue;
                    field.setAccessible(true);
                    if (!field.getType().isPrimitive() && field.get(bean) != null) continue;
                    field.set(bean, value(field.getGenericType(), hint.member(field.getName()), path, depth + 1));
                }
            }
            return bean;
        } finally { path.remove(clazz); }
    }

    private static boolean isBasic(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || type == String.class || Number.class.isAssignableFrom(type)
                || type == Boolean.class || type == Character.class || type.getName().startsWith("java.time.")
                || java.util.Date.class.isAssignableFrom(type) || type == UUID.class;
    }
    static Class<?> raw(Type type) {
        if (type instanceof Class<?> clazz) return clazz;
        if (type instanceof ParameterizedType parameterized) return raw(parameterized.getRawType());
        if (type instanceof GenericArrayType array) return Array.newInstance(raw(array.getGenericComponentType()), 0).getClass();
        if (type instanceof WildcardType wildcard) return raw(wildcard.getUpperBounds()[0]);
        if (type instanceof TypeVariable<?> variable) return raw(variable.getBounds()[0]);
        return Object.class;
    }
    private static Type argument(Type type, int index, Class<?> fallback) {
        return type instanceof ParameterizedType parameterized ? parameterized.getActualTypeArguments()[index]
                : fallback == null ? String.class : fallback;
    }
    private static String decapitalize(String name) {
        return name.length() > 1 && Character.isUpperCase(name.charAt(1)) ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
