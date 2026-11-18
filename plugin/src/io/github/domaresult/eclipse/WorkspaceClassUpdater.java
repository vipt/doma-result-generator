/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.eclipse;

import io.github.domaresult.core.GenerationException;
import io.github.domaresult.core.SourceUpdatePlan;
import io.github.domaresult.core.JavaSource;
import io.github.domaresult.core.JdbcErrorDetails;
import io.github.domaresult.core.EntityGenerator;
import io.github.domaresult.core.ExistingClassAction;
import io.github.domaresult.core.ResultColumn;
import io.github.domaresult.merge.EntityMerger;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.HashMap;
import java.util.List;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

/** Snapshot, validate in a private working copy, then update the saved workspace file. */
public final class WorkspaceClassUpdater {
    public record Snapshot(String source, long modificationStamp) {}

    private static final class TypeLookupFailure extends RuntimeException {
        TypeLookupFailure(JavaModelException cause) { super(cause); }
    }

    private Predicate<String> typeLookup(IJavaProject project) {
        var cache = new HashMap<String, Boolean>();
        return name -> cache.computeIfAbsent(name, key -> {
            try { return project.findType(key) != null; }
            catch (JavaModelException e) { throw new TypeLookupFailure(e); }
        });
    }

    public String generate(ICompilationUnit target, String pkg, String name, List<ResultColumn> columns) throws CoreException {
        try { return new EntityGenerator().generate(pkg, name, columns, typeLookup(target.getJavaProject())); }
        catch (TypeLookupFailure e) { throw (JavaModelException) e.getCause(); }
    }

    public EntityMerger.Result preview(ICompilationUnit target, String before, String generated,
            IProgressMonitor monitor, Consumer<JdbcErrorDetails.Stage> progress) throws CoreException {
        return preview(target, before, generated, ExistingClassAction.MERGE, monitor, progress);
    }

    public EntityMerger.Result preview(ICompilationUnit target, String before, String generated, ExistingClassAction action,
            IProgressMonitor monitor, Consumer<JdbcErrorDetails.Stage> progress) throws CoreException {
        java.util.Objects.requireNonNull(action, "Choose Merge or Overwrite before previewing an existing class.");
        EntityMerger.Result merged;
        if (action == ExistingClassAction.OVERWRITE) {
            progress.accept(JdbcErrorDetails.Stage.PREPARING_OVERWRITE);
            String replacement = generated;
            if (before.contains("\r\n")) replacement = replacement.replace("\r\n", "\n").replace("\n", "\r\n");
            if (before.startsWith("\uFEFF") && !replacement.startsWith("\uFEFF")) replacement = "\uFEFF" + replacement;
            merged = new EntityMerger.Result(replacement, List.of());
        } else {
            progress.accept(JdbcErrorDetails.Stage.MERGING_CLASS);
            var project = target.getJavaProject();
            try {
                merged = new EntityMerger().merge(before, generated, project.getOptions(true), typeLookup(project));
            } catch (TypeLookupFailure e) { throw (JavaModelException) e.getCause(); }
        }
        if (!before.equals(merged.source())) {
            progress.accept(JdbcErrorDetails.Stage.VALIDATING_CLASS);
            validate(target, merged.source(), monitor);
        }
        return merged;
    }

    public Snapshot snapshot(ICompilationUnit unit) throws CoreException {
        if (!unit.exists() || !(unit.getResource() instanceof IFile file)) {
            throw new GenerationException("The existing Java file is no longer available. Refresh Preview.");
        }
        var buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(unit.getPath(), LocationKind.IFILE);
        if (unit.hasUnsavedChanges() || buffer != null && buffer.isDirty()) {
            throw new GenerationException("Save the existing class and refresh Preview; it has unsaved edits.");
        }
        if (!file.isSynchronized(IResource.DEPTH_ZERO)) {
            throw new GenerationException("The existing file changed on disk. Refresh the file and Preview before updating.");
        }
        long stamp = file.getModificationStamp();
        try (var stream = file.getContents()) {
            String source = Charset.forName(file.getCharset()).newDecoder().decode(ByteBuffer.wrap(stream.readAllBytes())).toString();
            if (stamp != file.getModificationStamp()) throw new GenerationException("The class changed while reading. Refresh Preview.");
            return new Snapshot(source, stamp);
        } catch (IOException e) {
            throw new GenerationException("Cannot read the saved class in its current character encoding.");
        }
    }

    public void validate(ICompilationUnit target, String source, IProgressMonitor monitor) throws CoreException {
        WorkingCopyOwner owner = new WorkingCopyOwner() {};
        ICompilationUnit copy = target.getWorkingCopy(owner, monitor);
        try {
            copy.getBuffer().setContents(JavaSource.forParser(source));
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(copy);
            parser.setWorkingCopyOwner(owner);
            parser.setResolveBindings(true);
            var ast = (CompilationUnit) parser.createAST(monitor);
            if (ast == null) throw new GenerationException("Cannot validate the proposed class. Check the Java project build path.");
            for (var problem : ast.getProblems()) {
                if (problem.isError()) {
                    throw new GenerationException("The proposed class has a Java error on line " + problem.getSourceLineNumber()
                            + ": " + problem.getMessage() + " Resolve custom-code type conflicts or the project build path before updating.");
                }
            }
        } finally { copy.discardWorkingCopy(); }
    }

    public void apply(ICompilationUnit unit, SourceUpdatePlan plan) throws CoreException {
        if (!(unit.getResource() instanceof IFile file)) throw new GenerationException("The target must be a workspace Java file.");
        ResourcesPlugin.getWorkspace().run(monitor -> {
            Snapshot current = snapshot(unit);
            plan.verifyCurrent(current.source(), current.modificationStamp(), false);
            try {
                ByteBuffer encoded = Charset.forName(file.getCharset()).newEncoder().encode(CharBuffer.wrap(plan.after()));
                byte[] bytes = new byte[encoded.remaining()];
                encoded.get(bytes);
                file.setContents(new ByteArrayInputStream(bytes), IResource.KEEP_HISTORY, monitor);
            } catch (IOException e) {
                throw new GenerationException("Cannot encode the class in its current character encoding. No update was written.");
            }
        }, file, IWorkspace.AVOID_UPDATE, new NullProgressMonitor());
    }
}
