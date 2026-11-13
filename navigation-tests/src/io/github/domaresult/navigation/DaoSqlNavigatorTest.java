/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.navigation;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.IVMInstall;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/** Uses real workspace Java projects and JDT bindings, rather than mocked symbols. */
public class DaoSqlNavigatorTest {
    private static IVMInstall testVm;
    private final List<IProject> projects = new ArrayList<>();
    private IJavaProject daoProject;

    @BeforeClass public static void registerTestVm() {
        // A fresh headless workspace has no user-configured default JRE.
        var type = JavaRuntime.getVMInstallType("org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType");
        testVm = type.createVMInstall("doma-navigation-test-vm");
        testVm.setName("Navigation test JRE");
        testVm.setInstallLocation(new File(System.getProperty("java.home")));
    }

    @AfterClass public static void unregisterTestVm() {
        if (testVm != null) testVm.getVMInstallType().disposeVMInstall(testVm.getId());
    }

    @Before public void setUp() throws Exception {
        daoProject = project();
        unit(daoProject, "org.seasar.doma", "Dao.java", "public @interface Dao {}");
        unit(daoProject, "org.seasar.doma", "Script.java", "public @interface Script {}");
        unit(daoProject, "org.seasar.doma", "Sql.java", "public @interface Sql { String value(); }");
        unit(daoProject, "example", "EmployeeDao.java", """
                import org.seasar.doma.*;
                @Dao public interface EmployeeDao {
                    int selectById(int id);
                    @Script void setup();
                    @Sql("select 1") int inline();
                }
                """);
    }

    @After public void tearDown() throws Exception {
        for (int i = projects.size() - 1; i >= 0; i--) projects.get(i).delete(true, true, null);
    }

    @Test public void jumpsFromCallWithCaret() throws Exception {
        IFile sql = sql("selectById.sql");
        ICompilationUnit caller = caller("return dao.selectById(1);");
        int offset = caller.getSource().indexOf("selectById") + 4;
        assertEquals(List.of(sql), DaoSqlNavigator.findFiles(caller, offset, 0));
    }

    @Test public void jumpsFromSelectedMethodName() throws Exception {
        IFile sql = sql("selectById.sql");
        ICompilationUnit caller = caller("return dao.selectById(1);");
        assertEquals(List.of(sql), select(caller, "selectById"));
    }

    @Test public void legacyElementAtReturnsEnclosingServiceInsteadOfCalledDao() throws Exception {
        var declaration = unit(daoProject, "example", "MstSettingshopDao.java", """
                import org.seasar.doma.Dao;
                @Dao public interface MstSettingshopDao { int selectAssignShopCodes(int id); }
                """);
        var caller = unit(daoProject, "example", "SettingService.java", """
                class SettingService {
                    int createSettingCeremonyData(MstSettingshopDao mstSettingshopDao) {
                        return mstSettingshopDao.selectAssignShopCodes(1);
                    }
                }
                """);
        IFile sql = file(daoProject.getProject(),
                "src/main/resources/META-INF/example/MstSettingshopDao/selectAssignShopCodes.sql", "select 1");
        int offset = caller.getSource().indexOf("selectAssignShopCodes") + 4;

        // The public Doma Tools Java editor handler uses getElementAt(offset).
        var enclosing = (org.eclipse.jdt.core.IMethod) caller.getElementAt(offset);
        assertEquals("createSettingCeremonyData", enclosing.getElementName());
        assertEquals("example.SettingService", enclosing.getDeclaringType().getFullyQualifiedName());
        assertEquals(0, enclosing.getDeclaringType().getAnnotations().length);

        // Resolving the symbol, as F3 and our navigator do, finds the called DAO.
        var called = (org.eclipse.jdt.core.IMethod) caller.codeSelect(offset, 0)[0];
        assertEquals("selectAssignShopCodes", called.getElementName());
        assertEquals("example.MstSettingshopDao", called.getDeclaringType().getFullyQualifiedName());
        assertEquals(List.of(sql), DaoSqlNavigator.findFiles(caller, offset, 0));

        // getElementAt does work at the declaration, explaining the difference.
        var declared = (org.eclipse.jdt.core.IMethod) declaration.getElementAt(
                declaration.getSource().indexOf("selectAssignShopCodes") + 4);
        assertEquals("selectAssignShopCodes", declared.getElementName());
        assertEquals("example.MstSettingshopDao", declared.getDeclaringType().getFullyQualifiedName());
    }

    @Test public void stillJumpsFromDeclaration() throws Exception {
        IFile sql = sql("selectById.sql");
        var declaration = daoProject.findType("example.EmployeeDao").getCompilationUnit();
        assertEquals(List.of(sql), select(declaration, "selectById"));
    }

    @Test public void supportsMethodReferences() throws Exception {
        IFile sql = sql("selectById.sql");
        var caller = unit(daoProject, "example", "Service.java", """
                class Service {
                    java.util.function.IntUnaryOperator query(EmployeeDao dao) { return dao::selectById; }
                }
                """);
        assertNotNull("The test JRE must provide IntUnaryOperator: "
                + java.util.Arrays.toString(daoProject.getResolvedClasspath(true)),
                daoProject.findType("java.util.function.IntUnaryOperator"));
        var parser = org.eclipse.jdt.core.dom.ASTParser.newParser(org.eclipse.jdt.core.dom.AST.getJLSLatest());
        parser.setSource(caller);
        parser.setResolveBindings(true);
        var ast = (org.eclipse.jdt.core.dom.CompilationUnit) parser.createAST(null);
        assertEquals("Method reference must resolve: " + java.util.Arrays.toString(ast.getProblems()),
                0, ast.getProblems().length);
        assertEquals(List.of(sql), select(caller, "selectById"));
    }

    @Test public void returnsAllDialectVariantsWithoutPrefixCollisions() throws Exception {
        IFile generic = sql("selectById.sql");
        IFile postgres = sql("selectById-postgres.sql");
        sql("selectByIdExtra.sql");
        sql("selectById-.sql");
        sql("selectById.script");
        assertEquals(List.of(postgres, generic), select(caller("return dao.selectById(1);"), "selectById"));
    }

    @Test public void opensDialectFileWhenNoGenericFileExists() throws Exception {
        IFile sql = sql("selectById-oracle.sql");
        assertEquals(List.of(sql), select(caller("return dao.selectById(1);"), "selectById"));
    }

    @Test public void scriptUsesScriptExtension() throws Exception {
        IFile script = sql("setup.script");
        sql("setup.sql");
        assertEquals(List.of(script), select(caller("dao.setup(); return 0;"), "setup"));
    }

    @Test public void ignoresInlineSqlEvenWithStaleFile() throws Exception {
        sql("inline.sql");
        assertTrue(select(caller("return dao.inline();"), "inline").isEmpty());
    }

    @Test public void missingSqlDoesNotCreateFile() throws Exception {
        assertTrue(select(caller("return dao.selectById(1);"), "selectById").isEmpty());
        assertFalse(daoProject.getProject().getFolder("src/main/resources/META-INF").exists());
    }

    @Test public void ignoresReceiverVariableAndEnclosingServiceMethod() throws Exception {
        sql("selectById.sql");
        var caller = caller("return dao.selectById(1);");
        assertTrue(select(caller, "dao").isEmpty());
        assertTrue(select(caller, "find").isEmpty());
    }

    @Test public void ignoresSameNamedNonDaoMethod() throws Exception {
        sql("selectById.sql");
        var caller = unit(daoProject, "example", "Service.java", """
                class Service {
                    int selectById(int id) { return id; }
                    int find() { return selectById(1); }
                }
                """);
        int offset = caller.getSource().lastIndexOf("selectById");
        assertTrue(DaoSqlNavigator.findFiles(caller, offset, 10).isEmpty());
    }

    @Test public void doesNotConfuseUnrelatedDaoAnnotation() throws Exception {
        unit(daoProject, "fake", "Dao.java", "public @interface Dao {}");
        unit(daoProject, "example", "EmployeeDao.java", """
                import fake.Dao;
                @Dao public interface EmployeeDao { int selectById(int id); }
                """);
        sql("selectById.sql");
        assertTrue(select(caller("return dao.selectById(1);"), "selectById").isEmpty());
    }

    @Test public void supportsFullyQualifiedAnnotation() throws Exception {
        unit(daoProject, "example", "EmployeeDao.java", """
                @org.seasar.doma.Dao public interface EmployeeDao { int selectById(int id); }
                """);
        IFile sql = sql("selectById.sql");
        assertEquals(List.of(sql), select(caller("return dao.selectById(1);"), "selectById"));
    }

    @Test public void usesUnsavedWorkingCopy() throws Exception {
        IFile sql = sql("selectById.sql");
        ICompilationUnit caller = caller("return 0;");
        caller.becomeWorkingCopy(null);
        try {
            caller.getBuffer().setContents("package example; class Service { "
                    + "int find(EmployeeDao dao) { return dao.selectById(1); } }");
            assertEquals(List.of(sql), select(caller, "selectById"));
            try (var saved = ((IFile) caller.getResource()).getContents()) {
                assertFalse(new String(saved.readAllBytes(), StandardCharsets.UTF_8).contains("selectById"));
            }
        } finally {
            caller.discardWorkingCopy();
        }
    }

    @Test public void followsReferenceToDaoProject() throws Exception {
        IFile sql = sql("selectById.sql");
        IJavaProject app = project();
        var entries = new ArrayList<IClasspathEntry>(List.of(app.getRawClasspath()));
        entries.add(JavaCore.newProjectEntry(daoProject.getPath()));
        app.setRawClasspath(entries.toArray(IClasspathEntry[]::new), null);
        var caller = unit(app, "example", "Service.java", """
                class Service { int find(EmployeeDao dao) { return dao.selectById(1); } }
                """);
        // A similarly named resource in the caller's project is not the DAO's SQL.
        file(app.getProject(), "src/main/resources/META-INF/example/EmployeeDao/selectById.sql", "select 2");
        assertEquals(List.of(sql), select(caller, "selectById"));
    }

    @Test public void ignoresBuildOutputCopies() throws Exception {
        IFile sql = sql("selectById.sql");
        file(daoProject.getProject(), "bin/META-INF/example/EmployeeDao/selectById.sql", "select 1");
        assertEquals(List.of(sql), select(caller("return dao.selectById(1);"), "selectById"));
    }

    private List<IFile> select(ICompilationUnit unit, String method) throws Exception {
        int offset = unit.getSource().indexOf(method);
        assertTrue("Missing test selection: " + method, offset >= 0);
        return DaoSqlNavigator.findFiles(unit, offset, method.length());
    }

    private ICompilationUnit caller(String body) throws Exception {
        return unit(daoProject, "example", "Service.java",
                "class Service { int find(EmployeeDao dao) { " + body + " } }");
    }

    private IFile sql(String name) throws Exception {
        return file(daoProject.getProject(), "src/main/resources/META-INF/example/EmployeeDao/" + name, "select 1");
    }

    private ICompilationUnit unit(IJavaProject project, String pkg, String name, String source) throws Exception {
        var root = project.getPackageFragmentRoot(project.getProject().getFolder("src/main/java"));
        return root.createPackageFragment(pkg, true, null)
                .createCompilationUnit(name, "package " + pkg + ";\n" + source, true, null);
    }

    private IJavaProject project() throws Exception {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("navigation-" + UUID.randomUUID());
        project.create(null);
        project.open(null);
        projects.add(project);
        var description = project.getDescription();
        description.setNatureIds(new String[] {JavaCore.NATURE_ID});
        project.setDescription(description, null);
        folder(project.getFolder("src/main/java"));
        folder(project.getFolder("src/main/resources"));
        folder(project.getFolder("bin"));
        IJavaProject javaProject = JavaCore.create(project);
        var options = new HashMap<String, String>();
        JavaCore.setComplianceOptions(JavaCore.VERSION_17, options);
        javaProject.setOptions(options);
        javaProject.setRawClasspath(new IClasspathEntry[] {
                JavaCore.newSourceEntry(project.getFolder("src/main/java").getFullPath()),
                JavaCore.newSourceEntry(project.getFolder("src/main/resources").getFullPath()),
                JavaCore.newContainerEntry(JavaRuntime.newJREContainerPath(testVm))
        }, project.getFolder("bin").getFullPath(), null);
        return javaProject;
    }

    private IFile file(IProject project, String path, String contents) throws Exception {
        IFile file = project.getFile(path);
        if (file.getParent() instanceof IFolder parent) folder(parent);
        try (var in = new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8))) {
            file.create(in, true, null);
        }
        return file;
    }

    private void folder(IFolder folder) throws Exception {
        if (folder.exists()) return;
        if (folder.getParent() instanceof IFolder parent) folder(parent);
        folder.create(true, true, null);
    }
}
