/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Chooses short type names without changing the meaning of names already in scope. */
public final class JavaImports {
    private final String packageName;
    private final Map<String, String> names = new HashMap<>();
    private final Set<String> blocked = new HashSet<>();
    private final Set<String> imports = new TreeSet<>();

    public JavaImports(String packageName, String className) {
        this.packageName = packageName;
        reserve(className, packageName + "." + className);
    }

    public void reserve(String simpleName, String qualifiedName) {
        String previous = names.putIfAbsent(simpleName, qualifiedName);
        if (previous != null && !previous.equals(qualifiedName)) blocked.add(simpleName);
    }

    public void block(String simpleName) { blocked.add(simpleName); }

    public String use(String type) {
        int array = type.indexOf('[');
        String base = array < 0 ? type : type.substring(0, array);
        String dimensions = array < 0 ? "" : type.substring(array);
        int dot = base.lastIndexOf('.');
        if (dot < 0) return type;
        String name = base.substring(dot + 1);
        if (blocked.contains(name) || names.containsKey(name) && !names.get(name).equals(base)) return type;
        names.put(name, base);
        String owner = base.substring(0, dot);
        if (!owner.equals("java.lang") && !owner.equals(packageName)) imports.add(base);
        return name + dimensions;
    }

    public List<String> imports() { return List.copyOf(imports); }
}
