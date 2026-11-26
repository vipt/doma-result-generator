/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.eclipse;

import java.io.IOException;
import java.net.*;
import org.eclipse.core.net.proxy.IProxyData;
import org.eclipse.core.net.proxy.IProxyService;
import org.osgi.framework.FrameworkUtil;

/** Uses Eclipse Network Connections without changing JVM-global proxy/authenticator settings. */
public final class JdbcDownloadNetwork {
    private JdbcDownloadNetwork() {}
    public static HttpURLConnection open(URI uri) throws IOException {
        if (!"https".equals(uri.getScheme()) || !"repo.maven.apache.org".equals(uri.getHost())) throw new IOException("Unsupported driver repository.");
        var context = FrameworkUtil.getBundle(JdbcDownloadNetwork.class).getBundleContext();
        var reference = context.getServiceReference(IProxyService.class);
        if (reference == null) return (HttpURLConnection) uri.toURL().openConnection();
        try {
            IProxyService service = context.getService(reference);
            if (service == null) return (HttpURLConnection) uri.toURL().openConnection();
            IProxyData[] options = service.isProxiesEnabled() ? service.select(uri) : new IProxyData[0];
            IProxyData chosen = null;
            for (IProxyData proxy : options) if (proxy.getHost() != null && !proxy.getHost().isBlank() && proxy.getPort() > 0) {
                if (IProxyData.HTTP_PROXY_TYPE.equals(proxy.getType()) || IProxyData.HTTPS_PROXY_TYPE.equals(proxy.getType()) || IProxyData.SOCKS_PROXY_TYPE.equals(proxy.getType())) { chosen = proxy; break; }
            }
            if (chosen == null) return (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
            final String host = chosen.getHost(); final int port = chosen.getPort();
            Proxy.Type type = IProxyData.SOCKS_PROXY_TYPE.equals(chosen.getType()) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection(new Proxy(type, InetSocketAddress.createUnresolved(host, port)));
            if (chosen.isRequiresAuthentication()) {
                final String user = chosen.getUserId(), secret = chosen.getPassword();
                connection.setAuthenticator(new Authenticator() {
                    @Override protected PasswordAuthentication getPasswordAuthentication() {
                        return getRequestorType() == RequestorType.PROXY && host.equalsIgnoreCase(getRequestingHost()) && port == getRequestingPort()
                                && user != null && secret != null ? new PasswordAuthentication(user, secret.toCharArray()) : null;
                    }
                });
            }
            return connection;
        } finally { context.ungetService(reference); }
    }
}
