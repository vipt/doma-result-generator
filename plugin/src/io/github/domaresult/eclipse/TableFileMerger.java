/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.eclipse;

import io.github.domaresult.core.GenerationException;
import io.github.domaresult.core.JavaSource;
import java.util.*;
import org.eclipse.compare.rangedifferencer.IRangeComparator;
import org.eclipse.compare.rangedifferencer.RangeDifference;
import org.eclipse.compare.rangedifferencer.RangeDifferencer;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.*;

/** Java adoption is additive; later updates use Eclipse's three-way range differencer. */
public final class TableFileMerger {
    public record Result(String source, String note) {}

    public Result merge(String current, String generated, String baseline, boolean java) {
        generated = lineEndings(current, generated);
        if (current.equals(generated)) return new Result(current, "Unchanged");
        if (baseline != null) {
            if (java) return new Result(mergeJava(current, generated, lineEndings(current, baseline)), "Java member merge; manual members and comments retained");
            String base = normalize(baseline), left = normalize(current), right = normalize(generated);
            String result = threeWay(base, left, right);
            return new Result(lineEndings(current, result), "Three-way merge; manual edits retained");
        }
        if (!java) return new Result(current, "No previous generation baseline: existing SQL retained. Choose Overwrite to replace it.");
        return new Result(adopt(current, generated), "No previous generation baseline: existing Java members and class annotations retained; missing annotations/members/imports added.");
    }

    public static String lineEndings(String current, String generated) {
        String output = normalize(generated);
        if (current.contains("\r\n")) output = output.replace("\n", "\r\n");
        return current.startsWith("\uFEFF") ? "\uFEFF" + output : output;
    }
    private static String normalize(String source) {
        return (source.startsWith("\uFEFF") ? source.substring(1) : source).replace("\r\n", "\n").replace('\r', '\n');
    }
    private record Lines(List<String> lines) implements IRangeComparator {
        Lines(String source) { this(Arrays.asList(source.split("(?<=\n)", -1))); }
        @Override public int getRangeCount() { return lines.size(); }
        @Override public boolean rangesEqual(int i, IRangeComparator other, int j) { return lines.get(i).equals(((Lines) other).lines.get(j)); }
        @Override public boolean skipRangeComparison(int length, int maximum, IRangeComparator other) { return false; }
        String range(int start, int end) { return String.join("", lines.subList(start, end)); }
    }
    static String threeWay(String base, String current, String generated) {
        if (current.equals(base)) return generated;
        if (generated.equals(base) || current.equals(generated)) return current;
        var ancestor = new Lines(base);
        var left = new Lines(current);
        var right = new Lines(generated);
        StringBuilder merged = new StringBuilder();
        for (var range : RangeDifferencer.findRanges(ancestor, left, right)) {
            if (range.kind() == RangeDifference.CONFLICT || range.kind() == RangeDifference.ERROR) {
                throw new GenerationException("Merge conflict: manual and generated changes overlap. Keep this file, resolve it manually, or choose Overwrite and review the difference.");
            }
            merged.append(range.kind() == RangeDifference.RIGHT ? right.range(range.rightStart(), range.rightEnd())
                    : left.range(range.leftStart(), range.leftEnd()));
        }
        return merged.toString();
    }

    private static String adopt(String current, String generated) {
        CompilationUnit old = parse(current), fresh = parse(generated);
        if (old.types().size() != 1 || fresh.types().size() != 1
                || !(old.types().get(0) instanceof TypeDeclaration target)
                || !(fresh.types().get(0) instanceof TypeDeclaration source)
                || target.isInterface() != source.isInterface()
                || !target.getName().getIdentifier().equals(source.getName().getIdentifier())
                || !Objects.equals(packageName(old), packageName(fresh))) {
            throw new GenerationException("Cannot merge different Java types or packages. Choose Overwrite only if replacement is intended.");
        }
        Map<String, BodyDeclaration> members = new HashMap<>();
        for (Object item : target.bodyDeclarations()) {
            BodyDeclaration declaration = (BodyDeclaration) item;
            for (String key : keys(declaration)) members.put(key, declaration);
        }
        StringBuilder additions = new StringBuilder();
        for (Object item : source.bodyDeclarations()) {
            BodyDeclaration declaration = (BodyDeclaration) item;
            var keys = keys(declaration);
            boolean present = keys.stream().anyMatch(members::containsKey);
            if (present && declaration instanceof FieldDeclaration field) {
                for (String key : keys) {
                    if (!(members.get(key) instanceof FieldDeclaration existing)
                            || existing.fragments().size() != 1 || field.fragments().size() != 1
                            || !existing.getType().toString().equals(field.getType().toString())) {
                        throw new GenerationException("Existing field type differs from the generated field: " + key + ". Resolve the type before merging.");
                    }
                }
            }
            if (!present) additions.append("\n\n").append(indentation(generated, declaration)).append(slice(generated, declaration));
        }
        Map<String, String> imports = new HashMap<>();
        for (Object item : old.imports()) {
            ImportDeclaration imp = (ImportDeclaration) item;
            imports.put(importKey(imp), imp.toString());
        }
        StringBuilder addedImports = new StringBuilder();
        for (Object item : fresh.imports()) {
            ImportDeclaration imp = (ImportDeclaration) item;
            String previous = imports.putIfAbsent(importKey(imp), imp.toString());
            if (previous != null && !previous.equals(imp.toString())) throw new GenerationException("Conflicting Java import: " + importKey(imp));
            if (previous == null) addedImports.append('\n').append(imp.toString());
        }
        int importsAt = !old.imports().isEmpty() ? end((ASTNode) old.imports().get(old.imports().size() - 1))
                : old.getPackage() != null ? end(old.getPackage()) : 0;
        StringBuilder result = new StringBuilder(current);
        result.insert(end(target) - 1, lineEndings(current, additions.toString() + "\n").replace("\uFEFF", ""));
        Set<String> annotations = new HashSet<>();
        for (Object modifier : target.modifiers()) if (modifier instanceof Annotation annotation) annotations.add(annotationName(annotation));
        StringBuilder addedAnnotations = new StringBuilder();
        for (Object modifier : source.modifiers()) if (modifier instanceof Annotation annotation && annotations.add(annotationName(annotation))) {
            addedAnnotations.append('\n').append(slice(generated, annotation)).append('\n');
        }
        int annotationsAt = target.getJavadoc() == null ? target.getStartPosition() : end(target.getJavadoc());
        result.insert(annotationsAt, lineEndings(current, addedAnnotations.toString()).replace("\uFEFF", ""));
        result.insert(importsAt, lineEndings(current, addedImports.toString()).replace("\uFEFF", ""));
        return result.toString();
    }

    private record Edit(int start, int length, String text) {}
    private static String mergeJava(String current, String generated, String baseline) {
        CompilationUnit old = parse(current), fresh = parse(generated), base = parse(baseline);
        TypeDeclaration target = type(old), source = type(fresh), ancestor = type(base);
        if (!target.getName().getIdentifier().equals(source.getName().getIdentifier())
                || !ancestor.getName().getIdentifier().equals(source.getName().getIdentifier())
                || target.isInterface() != source.isInterface()
                || !Objects.equals(packageName(old), packageName(fresh)) || !Objects.equals(packageName(base), packageName(fresh))) {
            throw new GenerationException("Generated Java type/package changed. Refresh destinations before merging.");
        }
        Map<String, BodyDeclaration> previous = memberMap(ancestor), now = memberMap(target), next = memberMap(source);
        List<Edit> edits = new ArrayList<>();
        Set<String> removedFields = new HashSet<>(), removedMethods = new HashSet<>();
        Set<BodyDeclaration> processed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var item : previous.entrySet()) {
            BodyDeclaration before = item.getValue();
            if (!processed.add(before)) continue;
            BodyDeclaration existing = now.get(item.getKey()), replacement = next.get(item.getKey());
            String a = slice(baseline, before), l = existing == null ? "" : slice(current, existing), r = replacement == null ? "" : slice(generated, replacement);
            String merged = lineEndings(current, threeWay(normalize(a), normalize(l), normalize(r))).replace("\uFEFF", "");
            if (existing == null) {
                if (!merged.isEmpty()) throw new GenerationException("Merge conflict: a removed Java member changed in generated output: " + item.getKey());
            } else if (!l.equals(merged)) {
                edits.add(new Edit(existing.getStartPosition(), existing.getLength(), merged));
                if (merged.isEmpty()) {
                    if (existing instanceof FieldDeclaration field) removedFields.add(((VariableDeclarationFragment) field.fragments().get(0)).getName().getIdentifier());
                    if (existing instanceof MethodDeclaration method) removedMethods.add(method.getName().getIdentifier());
                }
            }
        }
        StringBuilder additions = new StringBuilder(); processed.clear();
        for (var item : next.entrySet()) {
            BodyDeclaration replacement = item.getValue();
            if (previous.containsKey(item.getKey()) || !processed.add(replacement)) continue;
            BodyDeclaration existing = now.get(item.getKey());
            if (existing != null && !slice(current, existing).equals(slice(generated, replacement))) {
                throw new GenerationException("Merge conflict: a custom member has the same signature as a new generated member: " + item.getKey());
            }
            if (existing == null) additions.append("\n\n").append(indentation(generated, replacement)).append(slice(generated, replacement));
        }
        if (!additions.isEmpty()) edits.add(new Edit(end(target) - 1, 0, lineEndings(current, additions + "\n").replace("\uFEFF", "")));
        String a = header(baseline, ancestor), l = header(current, target), r = header(generated, source);
        String mergedHeader = lineEndings(current, threeWay(normalize(a), normalize(l), normalize(r))).replace("\uFEFF", "");
        if (!mergedHeader.equals(l)) edits.add(new Edit(target.getStartPosition(), l.length(), mergedHeader));
        String imports = missingImports(old, fresh);
        if (!imports.isEmpty()) {
            int at = !old.imports().isEmpty() ? end((ASTNode) old.imports().get(old.imports().size() - 1)) : old.getPackage() != null ? end(old.getPackage()) : 0;
            edits.add(new Edit(at, 0, lineEndings(current, imports).replace("\uFEFF", "")));
        }
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        StringBuilder result = new StringBuilder(current);
        for (Edit edit : edits) result.replace(edit.start(), edit.start() + edit.length(), edit.text());
        CompilationUnit merged = parse(result.toString());
        merged.accept(new ASTVisitor() {
            @Override public boolean visit(SimpleName name) {
                if (removedFields.contains(name.getIdentifier()) && !name.isDeclaration()) throw new GenerationException("Merge would remove a field referenced by retained code: " + name.getIdentifier() + ". Resolve the reference before updating.");
                return true;
            }
            @Override public boolean visit(MethodInvocation call) {
                if (removedMethods.contains(call.getName().getIdentifier())) throw new GenerationException("Merge would remove a method called by retained code: " + call.getName() + ". Resolve the call before updating.");
                return true;
            }
        });
        return result.toString();
    }
    private static TypeDeclaration type(CompilationUnit unit) {
        if (unit.types().size() != 1 || !(unit.types().get(0) instanceof TypeDeclaration type)) throw new GenerationException("Merge requires one top-level class or interface.");
        return type;
    }
    private static Map<String, BodyDeclaration> memberMap(TypeDeclaration type) {
        Map<String, BodyDeclaration> result = new LinkedHashMap<>();
        for (Object member : type.bodyDeclarations()) {
            BodyDeclaration declaration = (BodyDeclaration) member;
            if (declaration instanceof FieldDeclaration field && field.fragments().size() != 1) throw new GenerationException("Split declarations containing multiple fields before merging.");
            for (String key : keys(declaration)) if (result.putIfAbsent(key, declaration) != null) throw new GenerationException("Duplicate Java member: " + key);
        }
        return result;
    }
    private static String header(String text, TypeDeclaration type) {
        int limit = type.bodyDeclarations().isEmpty() ? end(type) - 1 : ((ASTNode) type.bodyDeclarations().get(0)).getStartPosition();
        var scanner = org.eclipse.jdt.core.ToolFactory.createScanner(false, false, false, JavaCore.VERSION_17);
        scanner.setSource(text.toCharArray()); scanner.resetTo(type.getStartPosition(), limit - 1);
        int brace = -1;
        try {
            for (int token; (token = scanner.getNextToken()) != org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameEOF;) {
                if (token == org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameLBRACE) brace = scanner.getCurrentTokenEndPosition() + 1;
            }
        } catch (org.eclipse.jdt.core.compiler.InvalidInputException e) { throw new GenerationException("Cannot read the Java class declaration."); }
        if (brace < 0) throw new GenerationException("Cannot read the Java class body.");
        return text.substring(type.getStartPosition(), brace);
    }
    private static String missingImports(CompilationUnit old, CompilationUnit fresh) {
        Map<String, String> imports = new HashMap<>();
        for (Object item : old.imports()) { var imp = (ImportDeclaration) item; imports.put(importKey(imp), imp.toString()); }
        StringBuilder result = new StringBuilder();
        for (Object item : fresh.imports()) {
            var imp = (ImportDeclaration) item;
            String existing = imports.putIfAbsent(importKey(imp), imp.toString());
            if (existing != null && !existing.equals(imp.toString())) throw new GenerationException("Conflicting Java import: " + importKey(imp));
            if (existing == null) result.append('\n').append(imp.toString());
        }
        return result.toString();
    }
    private static String importKey(ImportDeclaration imp) {
        String name = imp.getName().getFullyQualifiedName();
        return (imp.isStatic() ? "static " : "") + (imp.isOnDemand() ? name + ".*" : name.substring(name.lastIndexOf('.') + 1));
    }
    private static String annotationName(Annotation annotation) {
        String name = annotation.getTypeName().getFullyQualifiedName();
        return name.substring(name.lastIndexOf('.') + 1);
    }
    private static List<String> keys(BodyDeclaration declaration) {
        if (declaration instanceof FieldDeclaration field) return ((List<?>) field.fragments()).stream()
                .map(v -> "field " + ((VariableDeclarationFragment) v).getName().getIdentifier()).toList();
        if (declaration instanceof MethodDeclaration method) return List.of("method " + method.getName() + "(" +
                String.join(",", ((List<?>) method.parameters()).stream().map(p -> {
                    var parameter = (SingleVariableDeclaration) p;
                    return erasedType(parameter.getType()) + (parameter.isVarargs() ? "[]" : "") + "[]".repeat(parameter.getExtraDimensions());
                }).toList()) + ")");
        if (declaration instanceof AbstractTypeDeclaration type) return List.of("type " + type.getName());
        // Initializers and other custom template members are preserved rather than duplicated.
        return List.of("other " + declaration.getNodeType());
    }
    private static String erasedType(Type type) {
        if (type instanceof ParameterizedType parameterized) return erasedType(parameterized.getType());
        if (type instanceof ArrayType array) return erasedType(array.getElementType()) + "[]".repeat(array.getDimensions());
        String name = type.toString();
        return name.substring(name.lastIndexOf('.') + 1);
    }
    private static String indentation(String text, ASTNode node) {
        String prefix = text.substring(text.lastIndexOf('\n', node.getStartPosition()) + 1, node.getStartPosition());
        return prefix.isBlank() ? prefix : "    ";
    }
    private static String packageName(CompilationUnit unit) { return unit.getPackage() == null ? null : unit.getPackage().getName().getFullyQualifiedName(); }
    private static int end(ASTNode node) { return node.getStartPosition() + node.getLength(); }
    private static String slice(String text, ASTNode node) { return text.substring(node.getStartPosition(), end(node)); }
    public static CompilationUnit parse(String text) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(JavaSource.forParser(text).toCharArray());
        Map<String, String> options = new HashMap<>();
        JavaCore.setComplianceOptions(JavaCore.VERSION_17, options);
        parser.setCompilerOptions(options);
        var ast = (CompilationUnit) parser.createAST(null);
        for (var error : ast.getProblems()) if (error.isError()) throw new GenerationException("Java syntax error at line " + error.getSourceLineNumber() + ". Resolve it before generating.");
        return ast;
    }
}
