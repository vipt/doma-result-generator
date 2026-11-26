/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import io.github.domaresult.core.JdbcDriverCatalog.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** Downloads only catalog-pinned HTTPS artifacts; unverified/partial files are never selected. */
public final class JdbcDriverDownloads {
    @FunctionalInterface public interface Transport { HttpURLConnection open(URI uri) throws IOException; }
    public record Progress(String file, long completedBytes, long totalBytes, boolean cached) {}
    private final Path cache;
    private final Transport transport;
    public JdbcDriverDownloads(Path cache) {
        this(cache, uri -> {
            if (!"https".equals(uri.getScheme()) || !"repo.maven.apache.org".equals(uri.getHost())) throw new IOException("Unsupported driver repository.");
            return (HttpURLConnection) uri.toURL().openConnection();
        });
    }
    public JdbcDriverDownloads(Path cache, Transport transport) { this.cache = cache.toAbsolutePath().normalize(); this.transport = transport; }
    public Path cache() { return cache; }
    public List<Path> download(Driver driver, Release release, PreviewExecution.Cancellation cancellation,
            Consumer<Progress> progress) throws IOException {
        List<Path> result = new ArrayList<>(); long completed = 0;
        for (int i = 0; i < release.jars().size(); i++) {
            cancellation.check(); Jar jar = release.jars().get(i); Path target = target(jar);
            String requiredClass = i == 0 ? driver.driverClass().replace('.', '/') + ".class" : null;
            if (valid(target, jar, requiredClass, cancellation)) {
                completed += jar.bytes(); progress.accept(new Progress(jar.filename(), completed, release.bytes(), true));
            } else {
                long previous = completed;
                fetch(jar, target, requiredClass, cancellation,
                        received -> progress.accept(new Progress(jar.filename(), previous + received, release.bytes(), false)));
                completed += jar.bytes();
            }
            result.add(target);
        }
        cancellation.check(); return List.copyOf(result);
    }
    private Path target(Jar jar) throws IOException {
        Path target = cache.resolve(jar.repositoryPath());
        for (Path path = target; path != null && !path.equals(cache); path = path.getParent()) {
            if (Files.isSymbolicLink(path)) throw new GenerationException("A symbolic link occupies the JDBC cache. Remove it or choose a manual driver JAR.");
        }
        return target;
    }
    private boolean valid(Path path, Jar jar, String requiredClass, PreviewExecution.Cancellation cancellation) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != jar.bytes()) return false;
        if (!sha256(path, cancellation).equals(jar.sha256())) return false;
        try (ZipFile zip = new ZipFile(path.toFile())) {
            return zip.getEntry("META-INF/MANIFEST.MF") != null && (requiredClass == null || zip.getEntry(requiredClass) != null);
        } catch (java.util.zip.ZipException e) { return false; }
    }
    private void fetch(Jar jar, Path target, String requiredClass, PreviewExecution.Cancellation cancellation,
            Consumer<Long> progress) throws IOException {
        cancellation.check(); Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".jdbc-", ".part");
        HttpURLConnection connection = null; AutoCloseable close = null;
        try {
            connection = transport.open(jar.uri());
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "Doma-Result-Generator/JDBC-downloader");
            HttpURLConnection active = connection; close = active::disconnect; cancellation.trackResource(close);
            int status = connection.getResponseCode(); cancellation.check();
            if (status != HttpURLConnection.HTTP_OK) throw new GenerationException("Driver download failed (HTTP " + status + ") for " + jar.filename() + ". Check network/proxy settings or use Browse.");
            long length = connection.getContentLengthLong();
            if (length >= 0 && length != jar.bytes()) throw mismatch(jar);
            try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[65536]; long received = 0;
                for (int size; (size = input.read(buffer)) != -1;) {
                    cancellation.check(); received += size;
                    if (received > jar.bytes()) throw mismatch(jar);
                    output.write(buffer, 0, size); progress.accept(received);
                }
                if (received != jar.bytes()) throw mismatch(jar);
            }
            cancellation.check();
            if (!valid(temporary, jar, requiredClass, cancellation)) throw mismatch(jar);
            cancellation.check(); target(jar); // Recheck cache links before publishing the verified file.
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            if (close != null) cancellation.releaseResource(close);
            if (connection != null) connection.disconnect();
            Files.deleteIfExists(temporary);
        }
    }
    private static GenerationException mismatch(Jar jar) {
        return new GenerationException("Driver size, SHA-256 or JAR contents did not match the catalog: " + jar.filename() + ". The download was not selected; retry or use Browse.");
    }
    private static String sha256(Path path, PreviewExecution.Cancellation cancellation) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int size; (size = input.read(buffer)) != -1;) { cancellation.check(); digest.update(buffer, 0, size); }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
