/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.merge;

import io.github.domaresult.core.GenerationException;
import io.github.domaresult.core.JavaImports;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.*;

/** Uses JDT source ranges; text outside targeted declarations is kept verbatim. */
public final class EntityMerger {
    public static final String MARKER = "@domaGenerated";
    public record Result(String source, List<String> notes) {
        public Result { notes = List.copyOf(notes); }
    }
    private record Patch(int start, int length, String text) {}
    private record Field(FieldDeclaration node, VariableDeclarationFragment fragment) {}

    public Result merge(String current, String generated) {
        return merge(current, generated, Map.of());
    }

    public Result merge(String current, String generated, Map<String, String> compilerOptions) {
        Model desiredInput = new Model(generated, null, compilerOptions);
        Model desired = new Model(qualifyGenerated(desiredInput), null, compilerOptions);
        Model existing = new Model(current, desired.type.getName().getIdentifier(), compilerOptions);
        if (!existing.packageName().equals(desired.packageName())) throw conflict("The existing package does not match the target package.");
        Annotation entity = existing.annotation(existing.type, "org.seasar.doma.Entity");
        if (entity == null) throw conflict("The existing class must be a Doma @Entity.");
        if (entity instanceof NormalAnnotation normal) {
            for (Object item : normal.values()) {
                MemberValuePair value = (MemberValuePair) item;
                if (value.getName().getIdentifier().equals("immutable") && !value.getValue().toString().equals("false")) {
                    throw conflict("Immutable entities require constructor updates and cannot be merged automatically.");
                }
            }
        }
        List<Patch> edits = new ArrayList<>();
        List<String> additions = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean alreadyManaged = existing.fields.values().stream().anyMatch(field -> managed(field.node))
                || java.util.Arrays.stream(existing.type.getMethods()).anyMatch(EntityMerger::managed);
        boolean legacy = !alreadyManaged && existing.type.getJavadoc() != null
                && existing.text(existing.type.getJavadoc()).contains("Generated from SELECT result metadata.");

        for (var entry : desired.fields.entrySet()) {
            String name = entry.getKey();
            Field wanted = entry.getValue();
            Field old = existing.fields.get(name);
            for (var other : existing.fields.entrySet()) {
                String otherLabel = existing.columnLabel(other.getValue().node);
                if (!other.getKey().equals(name) && otherLabel != null
                        && otherLabel.equalsIgnoreCase(desired.columnLabel(wanted.node))) {
                    throw conflict("Column '" + otherLabel + "' already belongs to field '" + other.getKey()
                            + "'. Restore its generated name or resolve the rename before previewing.");
                }
            }
            boolean adopt = old != null && legacy && existing.simpleField(old);
            if (old != null && !managed(old.node) && !adopt) {
                throw conflict("Field '" + name + "' is handwritten or has no generator ownership marker. It was preserved.");
            }
            if (old == null) additions.add(desired.text(wanted.node));
            else {
                if (old.node.fragments().size() != 1) throw conflict("Split the multiple-variable declaration for field '" + name + "' before updating.");
                boolean typeChanged = !existing.fieldType(old).equals(desired.fieldType(wanted));
                String label = existing.columnLabel(old.node);
                if (label == null) throw conflict("Restore @Column(name = ...) on generated field '" + name + "' before updating.");
                boolean labelChanged = !label.equals(desired.columnLabel(wanted.node));
                if ((typeChanged || labelChanged) && !existing.simpleField(old)) {
                    throw conflict("Field '" + name + "' has a custom initializer, dimensions or modifiers. It was preserved.");
                }
                if (typeChanged) existing.replaceType(edits, old.node.getType(), desired.text(wanted.node.getType()));
                if (labelChanged) {
                    StringLiteral literal = existing.columnLiteral(old.node);
                    edits.add(new Patch(literal.getStartPosition(), literal.getLength(), desired.text(desired.columnLiteral(wanted.node))));
                }
                existing.addMarker(edits, old.node);
                existing.makePrivate(edits, old.node);
            }
            String suffix = suffix(name);
            mergeGetter(existing, desired, old, wanted, name, "get" + suffix, legacy && adopt, additions, edits);
            mergeSetter(existing, desired, old, wanted, name, "set" + suffix, legacy && adopt, additions, edits);
        }

        for (var entry : existing.fields.entrySet()) {
            String name = entry.getKey();
            Field old = entry.getValue();
            if (desired.fields.containsKey(name)) continue;
            if (!managed(old.node)) {
                if (legacy && existing.columnLabel(old.node) != null) {
                    notes.add("Preserved unmarked legacy field '" + name + "'; its original ownership cannot be established.");
                }
                continue;
            }
            if (!existing.simpleField(old) || existing.hasCustomFieldAnnotations(old.node)) {
                throw conflict("Removed column's field '" + name + "' has custom declarations. It was preserved.");
            }
            String suffix = suffix(name);
            Set<ASTNode> removals = new HashSet<>();
            removals.add(old.node);
            for (MethodDeclaration method : existing.type.getMethods()) {
                String methodName = method.getName().getIdentifier();
                if (!managed(method)) continue;
                boolean getter = methodName.equals("get" + suffix) && method.parameters().isEmpty();
                boolean setter = methodName.equals("set" + suffix) && method.parameters().size() == 1;
                if (!getter && !setter) continue;
                if (getter ? !simpleGetter(method, name) : !simpleSetter(method, name)) {
                    throw conflict("Accessor '" + methodName + "' has a custom body. It was preserved.");
                }
                removals.add(method);
            }
            if (existing.references(name, removals)) {
                throw conflict("Column '" + name + "' was removed, but retained code references its field. Update those references or restore the SQL column.");
            }
            for (ASTNode node : removals) existing.removeKeepingComments(edits, node);
        }
        if (!additions.isEmpty()) {
            int close = existing.type.getStartPosition() + existing.type.getLength() - 1;
            String indent = existing.memberIndent();
            StringBuilder block = new StringBuilder(existing.newline);
            for (String source : additions) {
                String[] lines = source.replace("\r\n", "\n").split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    String line = i > 0 && lines[i].startsWith("    ") ? lines[i].substring(4) : lines[i];
                    block.append(indent).append(line).append(existing.newline);
                }
                block.append(existing.newline);
            }
            block.append(existing.indentAt(close));
            edits.add(new Patch(close, 0, block.toString()));
        }
        String merged = apply(current, edits);
        merged = shortenGenerated(new Model(merged, desired.type.getName().getIdentifier(), compilerOptions));
        new Model(merged, desired.type.getName().getIdentifier(), compilerOptions);
        return new Result(merged, notes);
    }

    private static List<ASTNode> generatedReferences(Model model) {
        List<ASTNode> references = new ArrayList<>();
        Annotation entity = model.annotation(model.type, "org.seasar.doma.Entity");
        if (entity != null) references.add(entity.getTypeName());
        for (Field field : model.fields.values()) {
            if (!managed(field.node)) continue;
            references.add(field.node.getType());
            Annotation column = model.annotation(field.node, "org.seasar.doma.Column");
            if (column != null) references.add(column.getTypeName());
        }
        for (MethodDeclaration method : model.type.getMethods()) {
            if (!managed(method)) continue;
            if (method.getReturnType2() != null) references.add(method.getReturnType2());
            for (Object item : method.parameters()) references.add(((SingleVariableDeclaration) item).getType());
        }
        return references;
    }

    private static String referenceType(Model model, ASTNode node) {
        return node instanceof Type type ? model.typeName(type) : model.resolve(((Name) node).getFullyQualifiedName());
    }

    private static String qualifyGenerated(Model model) {
        List<Patch> edits = new ArrayList<>();
        for (ASTNode node : generatedReferences(model)) {
            String name = referenceType(model, node);
            if (!model.text(node).equals(name)) model.replaceType(edits, node, name);
        }
        return apply(model.source, edits);
    }

    private static String shortenGenerated(Model model) {
        JavaImports imports = new JavaImports(model.packageName(), model.type.getName().getIdentifier());
        model.imports.forEach(imports::reserve);
        for (Object item : model.unit.imports()) {
            ImportDeclaration declaration = (ImportDeclaration) item;
            if (declaration.isStatic() && !declaration.isOnDemand()) {
                String name = declaration.getName().getFullyQualifiedName();
                imports.block(name.substring(name.lastIndexOf('.') + 1));
            }
        }
        List<ASTNode> references = generatedReferences(model);
        Set<ASTNode> ownedReferences = new HashSet<>(references);
        model.unit.accept(new ASTVisitor() {
            @Override public boolean preVisit2(ASTNode node) {
                if (ownedReferences.contains(node)) return false;
                preVisit(node);
                return true;
            }
            private void reserve(String name) {
                if (model.imports.containsKey(name)) imports.reserve(name, model.imports.get(name));
                else if (model.resolve(name).startsWith("java.lang.")) imports.reserve(name, model.resolve(name));
                else imports.block(name);
            }
            @Override public void preVisit(ASTNode node) {
                if (node instanceof AbstractTypeDeclaration declaration) imports.block(declaration.getName().getIdentifier());
                else if (node instanceof TypeParameter parameter) imports.block(parameter.getName().getIdentifier());
                else if (node instanceof SimpleType type && type.getName().isSimpleName()) reserve(type.getName().getFullyQualifiedName());
                else if (node instanceof Annotation annotation && annotation.getTypeName().isSimpleName()
                        && !ownedReferences.contains(annotation.getTypeName())) reserve(annotation.getTypeName().getFullyQualifiedName());
                else if (node instanceof MethodInvocation call && call.getExpression() instanceof SimpleName name) reserve(name.getIdentifier());
                else if (node instanceof QualifiedName name && name.getQualifier() instanceof SimpleName qualifier
                        && Character.isUpperCase(qualifier.getIdentifier().codePointAt(0))) reserve(qualifier.getIdentifier());
            }
        });
        List<Patch> edits = new ArrayList<>();
        for (ASTNode node : references) {
            String name = imports.use(referenceType(model, node));
            if (!model.text(node).equals(name)) model.replaceType(edits, node, name);
        }
        List<String> additions = imports.imports().stream().filter(name -> !model.imports.containsValue(name)).toList();
        if (!additions.isEmpty()) {
            String block = String.join(model.newline, additions.stream().map(name -> "import " + name + ";").toList()) + model.newline;
            if (!model.unit.imports().isEmpty()) {
                edits.add(new Patch(((ImportDeclaration) model.unit.imports().get(0)).getStartPosition(), 0, block));
            } else {
                PackageDeclaration pkg = model.unit.getPackage();
                int offset = pkg == null ? model.type.getStartPosition() : pkg.getStartPosition() + pkg.getLength();
                edits.add(new Patch(offset, 0, model.newline + model.newline + block));
            }
        }
        return apply(model.source, edits);
    }

    private void mergeGetter(Model old, Model desired, Field field, Field wanted, String name, String methodName,
            boolean adopt, List<String> additions, List<Patch> edits) {
        MethodDeclaration target = desired.methods(methodName, 0).get(0);
        List<MethodDeclaration> candidates = old.methods(methodName, 0);
        if (candidates.size() > 1) throw conflict("Multiple getter declarations: " + methodName);
        if (candidates.isEmpty()) { additions.add(desired.text(target)); return; }
        MethodDeclaration method = candidates.get(0);
        boolean owned = managed(method) || (adopt && simpleGetter(method, name));
        if (!old.returnType(method).equals(desired.returnType(target))) {
            if (!owned || !simpleGetter(method, name) || !method.extraDimensions().isEmpty()) {
                throw conflict("Getter '" + methodName + "' needs a type change but contains custom code or is handwritten. It was preserved.");
            }
            old.replaceType(edits, method.getReturnType2(), desired.text(target.getReturnType2()));
        }
        if (owned) old.addMarker(edits, method);
    }

    private void mergeSetter(Model old, Model desired, Field field, Field wanted, String name, String methodName,
            boolean adopt, List<String> additions, List<Patch> edits) {
        MethodDeclaration target = desired.methods(methodName, 1).get(0);
        String newType = desired.parameterType(target);
        List<MethodDeclaration> candidates = old.methods(methodName, 1);
        List<MethodDeclaration> marked = candidates.stream().filter(EntityMerger::managed).toList();
        if (marked.size() > 1) throw conflict("Multiple generated setters: " + methodName);
        MethodDeclaration method = marked.isEmpty() ? null : marked.get(0);
        if (method == null && field != null) {
            method = candidates.stream().filter(m -> old.parameterType(m).equals(old.fieldType(field))).findFirst().orElse(null);
        }
        if (method == null) method = candidates.stream().filter(m -> old.parameterType(m).equals(newType)).findFirst().orElse(null);
        if (method == null) { additions.add(desired.text(target)); return; }
        for (MethodDeclaration other : candidates) {
            if (other != method && old.parameterType(other).equals(newType)) throw conflict("Updating setter '" + methodName + "' would collide with a handwritten overload.");
        }
        boolean owned = managed(method) || (adopt && simpleSetter(method, name));
        if (!old.parameterType(method).equals(newType)) {
            SingleVariableDeclaration parameter = (SingleVariableDeclaration) method.parameters().get(0);
            if (!owned || !simpleSetter(method, name) || parameter.isVarargs() || !parameter.extraDimensions().isEmpty()) {
                throw conflict("Setter '" + methodName + "' needs a type change but contains custom code or is handwritten. It was preserved.");
            }
            old.replaceType(edits, parameter.getType(), desired.text(((SingleVariableDeclaration) target.parameters().get(0)).getType()));
        }
        if (owned) old.addMarker(edits, method);
    }

    private static boolean simpleGetter(MethodDeclaration method, String field) {
        if (Modifier.isStatic(method.getModifiers()) || method.isConstructor() || method.getBody() == null
                || !method.parameters().isEmpty() || method.getBody().statements().size() != 1) return false;
        Object statement = method.getBody().statements().get(0);
        return statement instanceof ReturnStatement value && fieldExpression(value.getExpression(), field);
    }

    private static boolean simpleSetter(MethodDeclaration method, String field) {
        if (Modifier.isStatic(method.getModifiers()) || method.getBody() == null || method.parameters().size() != 1
                || method.getBody().statements().size() != 1 || method.getReturnType2() == null
                || !method.getReturnType2().toString().equals("void")) return false;
        Object statement = method.getBody().statements().get(0);
        if (!(statement instanceof ExpressionStatement expression) || !(expression.getExpression() instanceof Assignment assignment)
                || assignment.getOperator() != Assignment.Operator.ASSIGN) return false;
        String parameter = ((SingleVariableDeclaration) method.parameters().get(0)).getName().getIdentifier();
        Expression left = assignment.getLeftHandSide();
        return fieldExpression(left, field) && !(left instanceof SimpleName && field.equals(parameter))
                && assignment.getRightHandSide() instanceof SimpleName right && right.getIdentifier().equals(parameter);
    }

    private static boolean fieldExpression(Expression expression, String name) {
        return expression instanceof SimpleName simple && simple.getIdentifier().equals(name)
                || expression instanceof FieldAccess access && access.getExpression() instanceof ThisExpression self
                && self.getQualifier() == null && access.getName().getIdentifier().equals(name);
    }

    private static boolean managed(BodyDeclaration declaration) {
        Javadoc doc = declaration.getJavadoc();
        if (doc == null) return false;
        for (Object tag : doc.tags()) if (MARKER.equals(((TagElement) tag).getTagName())) return true;
        return false;
    }

    private static String suffix(String name) {
        int first = name.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(first))) + name.substring(Character.charCount(first));
    }

    private static String apply(String source, List<Patch> edits) {
        edits.sort(Comparator.comparingInt(Patch::start).reversed());
        StringBuilder result = new StringBuilder(source);
        int boundary = source.length();
        for (Patch edit : edits) {
            if (edit.start + edit.length > boundary) throw conflict("Overlapping source changes require manual review.");
            result.replace(edit.start, edit.start + edit.length, edit.text);
            boundary = edit.start;
        }
        return result.toString();
    }

    private static GenerationException conflict(String message) { return new GenerationException(message); }

    private static final class Model {
        final String source, newline;
        final CompilationUnit unit;
        final TypeDeclaration type;
        final Map<String, Field> fields = new LinkedHashMap<>();
        final Map<String, String> imports = new HashMap<>();
        final List<String> wildcardImports = new ArrayList<>();

        Model(String source, String className, Map<String, String> compilerOptions) {
            this.source = source;
            this.newline = source.contains("\r\n") ? "\r\n" : "\n";
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            Map<String, String> options = new HashMap<>();
            JavaCore.setComplianceOptions(JavaCore.VERSION_17, options);
            options.putAll(compilerOptions);
            options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
            parser.setCompilerOptions(options);
            parser.setSource(source.toCharArray());
            parser.setResolveBindings(false);
            this.unit = (CompilationUnit) parser.createAST(null);
            for (var problem : unit.getProblems()) {
                if (problem.isError()) throw conflict("Fix Java syntax errors before updating (line " + problem.getSourceLineNumber() + ").");
            }
            TypeDeclaration match = null;
            for (Object candidate : unit.types()) {
                if (candidate instanceof TypeDeclaration declaration && !declaration.isInterface()
                        && (className == null || declaration.getName().getIdentifier().equals(className))) {
                    if (match != null) throw conflict("The target class is ambiguous.");
                    match = declaration;
                }
            }
            if (match == null) throw conflict("The existing file must contain the target top-level mutable class.");
            this.type = match;
            for (Object item : unit.imports()) {
                ImportDeclaration declaration = (ImportDeclaration) item;
                if (declaration.isStatic()) continue;
                String name = declaration.getName().getFullyQualifiedName();
                if (declaration.isOnDemand()) wildcardImports.add(name);
                else imports.put(name.substring(name.lastIndexOf('.') + 1), name);
            }
            for (FieldDeclaration field : type.getFields()) {
                for (Object item : field.fragments()) {
                    VariableDeclarationFragment fragment = (VariableDeclarationFragment) item;
                    if (fields.put(fragment.getName().getIdentifier(), new Field(field, fragment)) != null) {
                        throw conflict("Duplicate field declaration: " + fragment.getName());
                    }
                }
            }
        }

        String packageName() { return unit.getPackage() == null ? "" : unit.getPackage().getName().getFullyQualifiedName(); }
        String text(ASTNode node) { return source.substring(node.getStartPosition(), node.getStartPosition() + node.getLength()); }
        String fieldType(Field field) { return typeName(field.node.getType()) + "[]".repeat(field.fragment.extraDimensions().size()); }
        String returnType(MethodDeclaration method) { return method.getReturnType2() == null ? "" : typeName(method.getReturnType2()) + "[]".repeat(method.extraDimensions().size()); }
        String parameterType(MethodDeclaration method) {
            SingleVariableDeclaration parameter = (SingleVariableDeclaration) method.parameters().get(0);
            return typeName(parameter.getType()) + "[]".repeat(parameter.extraDimensions().size() + (parameter.isVarargs() ? 1 : 0));
        }
        String typeName(Type node) {
            if (node instanceof ArrayType array) return typeName(array.getElementType()) + "[]".repeat(array.getDimensions());
            if (node instanceof SimpleType simple) return resolve(simple.getName().getFullyQualifiedName());
            return node.toString().replaceAll("\\s+", "");
        }
        String resolve(String name) {
            if (name.contains(".")) return name;
            if (imports.containsKey(name)) return imports.get(name);
            try { Class.forName("java.lang." + name, false, null); return "java.lang." + name; }
            catch (ClassNotFoundException ignored) {}
            if (wildcardImports.size() == 1) return wildcardImports.get(0) + "." + name;
            return packageName().isEmpty() ? name : packageName() + "." + name;
        }
        List<MethodDeclaration> methods(String name, int count) {
            return java.util.Arrays.stream(type.getMethods()).filter(method -> !method.isConstructor()
                    && method.getName().getIdentifier().equals(name) && method.parameters().size() == count).toList();
        }
        Annotation annotation(BodyDeclaration declaration, String name) {
            for (Object modifier : declaration.modifiers()) {
                if (modifier instanceof Annotation annotation && resolve(annotation.getTypeName().getFullyQualifiedName()).equals(name)) return annotation;
            }
            return null;
        }
        StringLiteral columnLiteral(FieldDeclaration field) {
            Annotation annotation = annotation(field, "org.seasar.doma.Column");
            if (annotation instanceof NormalAnnotation normal) {
                for (Object item : normal.values()) {
                    MemberValuePair value = (MemberValuePair) item;
                    if (value.getName().getIdentifier().equals("name") && value.getValue() instanceof StringLiteral literal) return literal;
                }
            }
            return null;
        }
        String columnLabel(FieldDeclaration field) {
            StringLiteral literal = columnLiteral(field);
            return literal == null ? null : literal.getLiteralValue();
        }
        boolean simpleField(Field field) {
            return field.node.fragments().size() == 1 && field.fragment.getInitializer() == null
                    && field.fragment.extraDimensions().isEmpty() && !Modifier.isStatic(field.node.getModifiers())
                    && !Modifier.isFinal(field.node.getModifiers()) && columnLabel(field.node) != null;
        }
        boolean hasCustomFieldAnnotations(FieldDeclaration field) {
            for (Object item : field.modifiers()) {
                if (item instanceof Annotation annotation
                        && !resolve(annotation.getTypeName().getFullyQualifiedName()).equals("org.seasar.doma.Column")) return true;
            }
            return false;
        }
        String indentAt(int offset) {
            int line = source.lastIndexOf('\n', offset - 1) + 1;
            String prefix = source.substring(line, offset);
            return prefix.isBlank() ? prefix : "";
        }
        String memberIndent() {
            for (FieldDeclaration field : type.getFields()) {
                String indent = indentAt(field.getStartPosition());
                if (!indent.isEmpty()) return indent;
            }
            return indentAt(type.getStartPosition()) + "    ";
        }
        void addMarker(List<Patch> edits, BodyDeclaration node) {
            if (managed(node)) return;
            Javadoc doc = node.getJavadoc();
            String indent = indentAt(node.getStartPosition());
            if (doc == null) edits.add(new Patch(node.getStartPosition(), 0, "/** " + MARKER + " */" + newline + indent));
            else edits.add(new Patch(doc.getStartPosition() + doc.getLength() - 2, 0, newline + indent + " * " + MARKER + newline + indent + " "));
        }
        void makePrivate(List<Patch> edits, FieldDeclaration field) {
            for (Object item : field.modifiers()) {
                if (item instanceof Modifier modifier && (modifier.isPrivate() || modifier.isPublic() || modifier.isProtected())) {
                    if (!modifier.isPrivate()) edits.add(new Patch(modifier.getStartPosition(), modifier.getLength(), "private"));
                    return;
                }
            }
            edits.add(new Patch(field.getType().getStartPosition(), 0, "private "));
        }
        List<Comment> commentsWithin(ASTNode node) {
            List<Comment> comments = new ArrayList<>();
            for (Object item : unit.getCommentList()) {
                Comment comment = (Comment) item;
                if (comment.getStartPosition() >= node.getStartPosition()
                        && comment.getStartPosition() + comment.getLength() <= node.getStartPosition() + node.getLength()) comments.add(comment);
            }
            return comments;
        }
        void replaceType(List<Patch> edits, ASTNode node, String type) {
            StringBuilder comments = new StringBuilder();
            for (Comment comment : commentsWithin(node)) comments.append(text(comment)).append(comment.isLineComment() ? newline + indentAt(node.getStartPosition()) : " ");
            edits.add(new Patch(node.getStartPosition(), node.getLength(), comments + type));
        }
        void removeKeepingComments(List<Patch> edits, ASTNode node) {
            List<String> retained = new ArrayList<>();
            for (Comment comment : commentsWithin(node)) {
                String raw = text(comment);
                if (comment instanceof Javadoc doc) {
                    List<TagElement> markers = new ArrayList<>();
                    for (Object tag : doc.tags()) if (MARKER.equals(((TagElement) tag).getTagName())) markers.add((TagElement) tag);
                    markers.sort(Comparator.comparingInt(ASTNode::getStartPosition).reversed());
                    StringBuilder cleaned = new StringBuilder(raw);
                    for (TagElement marker : markers) {
                        int offset = marker.getStartPosition() - comment.getStartPosition();
                        cleaned.delete(offset, offset + MARKER.length());
                    }
                    raw = cleaned.toString();
                    if (raw.replaceAll("[\\s/*]", "").isEmpty()) continue;
                }
                retained.add(raw);
            }
            edits.add(new Patch(node.getStartPosition(), node.getLength(), String.join(newline + indentAt(node.getStartPosition()), retained)));
        }
        boolean references(String field, Set<ASTNode> excluded) {
            boolean[] found = { false };
            type.accept(new ASTVisitor() {
                @Override public boolean preVisit2(ASTNode node) { return !excluded.contains(node); }
                @Override public boolean visit(SimpleName name) {
                    if (name.isDeclaration()) return true;
                    ASTNode parent = name.getParent();
                    if (parent instanceof MethodInvocation call && call.getName() == name) return true;
                    if (name.getIdentifier().equals(field)) found[0] = true;
                    return true;
                }
            });
            return found[0];
        }
    }
}
