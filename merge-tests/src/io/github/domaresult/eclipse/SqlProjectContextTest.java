/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.eclipse;

import static org.junit.Assert.*;
import io.github.domaresult.core.*;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.eclipse.core.resources.*;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.launching.*;
import org.junit.*;

/** Real JDT source signatures, compiled DTOs, runtime classpath and unchanged workspace SQL. */
public class SqlProjectContextTest {
    private static IVMInstall vm;
    private IProject project;
    private IJavaProject java;
    private IFile sql;

    @BeforeClass public static void registerVm() {
        var type=JavaRuntime.getVMInstallType("org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType");
        vm=type.createVMInstall("doma-auto-argument-test-vm");
        vm.setName("Automatic argument test JRE");vm.setInstallLocation(new File(System.getProperty("java.home")));
    }
    @AfterClass public static void unregisterVm() { if(vm!=null)vm.getVMInstallType().disposeVMInstall(vm.getId()); }
    @Before public void setUp()throws Exception {
        project=ResourcesPlugin.getWorkspace().getRoot().getProject("auto-args-"+UUID.randomUUID());
        project.create(null);project.open(null);
        var description=project.getDescription();description.setNatureIds(new String[]{JavaCore.NATURE_ID});
        project.setDescription(description,null);project.setDefaultCharset("UTF-8",null);
        project.getFolder("src").create(true,true,null);project.getFolder("bin").create(true,true,null);
        java=JavaCore.create(project);var options=new HashMap<String,String>();JavaCore.setComplianceOptions(JavaCore.VERSION_17,options);java.setOptions(options);
        java.setRawClasspath(new IClasspathEntry[]{JavaCore.newSourceEntry(project.getFolder("src").getFullPath()),JavaCore.newContainerEntry(JavaRuntime.newJREContainerPath(vm))},project.getFolder("bin").getFullPath(),null);
        unit("example","Condition.java","package example; public class Condition { public String name; public java.time.LocalDate date; public String getDateString(){return date.toString();} }");
        unit("example.dao","ExampleDao.java","package example.dao; import java.util.List; import example.Condition; public interface ExampleDao { String find(Condition condition,List<Long> ids,boolean flag); }");
        sql=file("src/META-INF/example/dao/ExampleDao/find.sql","SELECT /*%if flag*/1/*%else*/2/*%end*/ AS id WHERE AND name=/*condition.name*/'x' AND id IN /*ids*/(1)");
        project.build(IncrementalProjectBuilder.FULL_BUILD,null);
        var errors=Arrays.stream(project.findMarkers(IMarker.PROBLEM,true,IResource.DEPTH_INFINITE)).filter(marker->marker.getAttribute(IMarker.SEVERITY,0)==IMarker.SEVERITY_ERROR).toList();
        assertTrue(errors.toString(),errors.isEmpty());
    }
    @After public void tearDown()throws Exception { if(project!=null && project.exists())project.delete(true,true,null); }

    @Test public void discoversDaoNamesTypesGenericArgumentsAndCompiledDtosWithoutInputs()throws Exception {
        byte[] before;try(var in=sql.getContents()){before=in.readAllBytes();}
        try(var context=SqlProjectContext.open(sql)) {
            var types=context.parameterTypes(Set.of("condition","ids","flag"));
            assertEquals("example.Condition",types.get("condition").getTypeName());
            var list=(java.lang.reflect.ParameterizedType)types.get("ids");
            assertEquals(List.class,list.getRawType());assertEquals(Long.class,list.getActualTypeArguments()[0]);
            assertEquals(boolean.class,types.get("flag"));
            var prepared=DomaSql.prepare(new String(before,StandardCharsets.UTF_8),SqlInput.Dialect.SQLSERVER,"",context.classLoader(),context::parameterTypes,new PreviewExecution.Cancellation());
            assertEquals(2,prepared.parameterCount());assertFalse(prepared.sql().contains("WHERE AND"));
        }
        try(var in=sql.getContents()){assertArrayEquals(before,in.readAllBytes());}
    }

    @Test public void dialectSpecificSqlFilesResolveTheSameDaoMethod()throws Exception {
        IFile variant=file("src/META-INF/example/dao/ExampleDao/find-sqlserver.sql","SELECT 1");
        try(var context=SqlProjectContext.open(variant)) { assertEquals(boolean.class,context.parameterTypes(Set.of("flag")).get("flag")); }
    }

    @Test public void unusedParametersAreNotLoadedAndPlainSqlNeedsNoDao()throws Exception {
        try(var context=SqlProjectContext.open(sql)) { assertTrue(context.parameterTypes(Set.of()).isEmpty()); }
        IFile standalone=file("src/standalone.sql","SELECT 1 WHERE AND id=/*id*/1");
        try(var context=SqlProjectContext.open(standalone)) {
            var prepared=DomaSql.prepare("SELECT 1 WHERE AND id=/*id*/1",SqlInput.Dialect.STANDARD,"",context.classLoader(),context::parameterTypes,new PreviewExecution.Cancellation());
            assertEquals(1,prepared.parameterCount());
        }
    }

    @Test public void compiledProjectMethodsAndConstantsUseTheAutomaticRuntimeClasspath()throws Exception {
        unit("example","Constants.java","package example; public class Constants { public static String code(){return \"x\";} }");
        project.build(IncrementalProjectBuilder.FULL_BUILD,null);
        try(var context=SqlProjectContext.open(sql)) {
            var prepared=DomaSql.prepare("SELECT 1 WHERE /*%if condition.getDateString()!=null*/AND name=/*@example.Constants@code()*/'x'/*%end*/",SqlInput.Dialect.STANDARD,"",context.classLoader(),context::parameterTypes,new PreviewExecution.Cancellation());
            assertEquals(1,prepared.parameterCount());
        }
    }

    private void unit(String pkg,String name,String source)throws Exception {
        java.getPackageFragmentRoot(project.getFolder("src")).createPackageFragment(pkg,true,null).createCompilationUnit(name,source,true,null);
    }
    private IFile file(String path,String text)throws Exception {
        var target=project.getFile(path);folder(target.getParent());
        target.create(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),true,null);return target;
    }
    private static void folder(IContainer container)throws Exception {
        if(container instanceof IFolder f && !f.exists()){folder(f.getParent());f.create(true,true,null);}
    }
}
