/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import static org.junit.Assert.*;
import io.github.domaresult.core.JdbcDriverCatalog;
import io.github.domaresult.eclipse.JdbcDownloadNetwork;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.net.proxy.*;
import org.osgi.framework.*;
import org.junit.Test;

public class JdbcDownloadIntegrationTest {
    @Test public void eclipseProxySelectionReceivesOnlyTheFixedRepositoryUrlAndDoesNotConnect() throws Exception {
        BundleContext context=FrameworkUtil.getBundle(JdbcDownloadIntegrationTest.class).getBundleContext();
        AtomicReference<URI> selected=new AtomicReference<>();
        IProxyService service=(IProxyService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{IProxyService.class},(p,m,a) -> {
            if(m.getName().equals("isProxiesEnabled"))return true;
            if(m.getName().equals("select")){selected.set((URI)a[0]);return new IProxyData[0];}
            if(m.getName().equals("hashCode"))return System.identityHashCode(p);
            if(m.getName().equals("equals"))return p==a[0];
            return null;
        });
        var properties=new Hashtable<String,Object>();properties.put(Constants.SERVICE_RANKING,Integer.MAX_VALUE);
        ServiceRegistration<IProxyService> registration=context.registerService(IProxyService.class,service,properties);
        try {
            URI repository=JdbcDriverCatalog.drivers().get(0).releases().get(0).jars().get(0).uri();
            var connection=JdbcDownloadNetwork.open(repository);
            assertEquals(repository,selected.get());assertEquals(repository.toURL(),connection.getURL());assertNull(connection.getRequestProperty("Authorization"));connection.disconnect();
        } finally {registration.unregister();}
    }
    @Test public void rejectsOtherSchemesAndHostsBeforeAnyProxyOrNetworkAccess() {
        for(String url:List.of("http://repo.maven.apache.org/maven2/x.jar","https://example.invalid/x.jar")) {
            assertThrows(java.io.IOException.class,() -> JdbcDownloadNetwork.open(URI.create(url)));
        }
    }
    @Test public void verifiedDownloadSelectionSerializesAllJarPathsForBothExistingConnectionPanels() {
        var selection=new JdbcDriverDownloadDialog.Selection(List.of(Path.of("/tmp/driver.jar"),Path.of("/tmp/dependency.jar")),"example.Driver");
        assertEquals("/tmp/driver.jar;/tmp/dependency.jar",selection.paths());assertEquals("example.Driver",selection.driverClass());
    }
}
