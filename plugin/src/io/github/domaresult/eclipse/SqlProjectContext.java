/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.eclipse;

import io.github.domaresult.core.GenerationException;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.launching.JavaRuntime;

/** Discovers the SQL's DAO parameter types and compiled project classpath without extra inputs. */
public final class SqlProjectContext implements AutoCloseable {
    private final URLClassLoader loader;
    private final IJavaProject project;
    private final String daoName, methodName;

    private SqlProjectContext(URLClassLoader loader, IJavaProject project, String daoName, String methodName) {
        this.loader = loader; this.project = project; this.daoName = daoName; this.methodName = methodName;
    }

    public static SqlProjectContext open(IFile sql) throws CoreException {
        IJavaProject project = JavaCore.create(sql.getProject());
        try {
            LinkedHashSet<URL> urls = new LinkedHashSet<>();
            for (String path : JavaRuntime.computeDefaultRuntimeClassPath(project)) urls.add(Path.of(path).toUri().toURL());
            String[] segments = sql.getProjectRelativePath().segments();
            String dao = "";
            for (int i = 0; i < segments.length - 1; i++) {
                if (segments[i].equals("META-INF")) dao = String.join(".", Arrays.copyOfRange(segments, i + 1, segments.length - 1));
            }
            String method = sql.getName().substring(0, sql.getName().length() - 4);
            int suffix = method.indexOf('-');
            if (suffix >= 0) method = method.substring(0, suffix);
            return new SqlProjectContext(new URLClassLoader(urls.toArray(URL[]::new), SqlProjectContext.class.getClassLoader()), project, dao, method);
        } catch (java.net.MalformedURLException | java.nio.file.InvalidPathException e) {
            throw new GenerationException("Cannot read the project's Java build path for automatic arguments.");
        }
    }

    public ClassLoader classLoader() { return loader; }

    public Map<String, Type> parameterTypes(Set<String> needed) {
        if (needed.isEmpty() || daoName.isEmpty()) return Map.of();
        try {
            IType dao = project.findType(daoName);
            if (dao == null) return Map.of();
            IMethod selected = null;
            for (IMethod method : dao.getMethods()) {
                if (!method.getElementName().equals(methodName)) continue;
                if (selected != null) throw new GenerationException("The SQL file matches more than one DAO method. Check the DAO method names.");
                selected = method;
            }
            if (selected == null) return Map.of();
            Map<String, Type> types = new LinkedHashMap<>();
            ILocalVariable[] parameters = selected.getParameters();
            String[] signatures = selected.getParameterTypes();
            for (int i = 0; i < parameters.length; i++) {
                String name = parameters[i].getElementName();
                if (needed.contains(name)) types.put(name, resolve(signatures[i], dao));
            }
            return types;
        } catch (CoreException | ClassNotFoundException | LinkageError e) {
            throw new GenerationException("Cannot load the DAO parameter types for automatic arguments. Build the project and check its Java build path and dependencies.");
        }
    }

    private Type resolve(String signature, IType context) throws JavaModelException, ClassNotFoundException {
        int kind = Signature.getTypeSignatureKind(signature);
        if (kind == Signature.ARRAY_TYPE_SIGNATURE) {
            Type element = resolve(signature.substring(1), context);
            return element instanceof Class<?> clazz ? Array.newInstance(clazz, 0).getClass() : new ArrayType(element);
        }
        if (kind == Signature.WILDCARD_TYPE_SIGNATURE) return signature.equals("*") ? Object.class : resolve(signature.substring(1), context);
        if (kind == Signature.TYPE_VARIABLE_SIGNATURE) return Object.class;
        if (kind == Signature.BASE_TYPE_SIGNATURE) return switch (signature) {
            case "Z" -> boolean.class; case "B" -> byte.class; case "C" -> char.class; case "D" -> double.class;
            case "F" -> float.class; case "I" -> int.class; case "J" -> long.class; case "S" -> short.class; default -> void.class;
        };
        String name = Signature.toString(Signature.getTypeErasure(signature));
        Class<?> raw;
        try { raw = load(name); }
        catch (ClassNotFoundException unresolved) {
            String[][] candidates = context.resolveType(name);
            if (candidates == null || candidates.length != 1) throw unresolved;
            raw = load((candidates[0][0].isEmpty() ? "" : candidates[0][0] + ".") + candidates[0][1].replace('.', '$'));
        }
        String[] arguments = Signature.getTypeArguments(signature);
        if (arguments.length == 0) return raw;
        Type[] types = new Type[arguments.length];
        for (int i = 0; i < arguments.length; i++) types[i] = resolve(arguments[i], context);
        return new GenericType(raw, types);
    }

    private Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private record GenericType(Class<?> type, Type[] arguments) implements ParameterizedType {
        @Override public Type[] getActualTypeArguments() { return arguments.clone(); }
        @Override public Type getRawType() { return type; }
        @Override public Type getOwnerType() { return type.getDeclaringClass(); }
    }
    private record ArrayType(Type component) implements GenericArrayType {
        @Override public Type getGenericComponentType() { return component; }
    }
    @Override public void close() throws IOException { loader.close(); }
}
