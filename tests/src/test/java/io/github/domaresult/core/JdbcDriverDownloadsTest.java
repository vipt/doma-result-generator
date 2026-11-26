/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import static org.junit.jupiter.api.Assertions.*;
import io.github.domaresult.core.JdbcDriverCatalog.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcDriverDownloadsTest {
    @TempDir Path temp;
    private static final String TYPE="example.Driver";
    private byte[] jar(String type) throws Exception {
        var bytes=new ByteArrayOutputStream(); var manifest=new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version","1.0");
        try(var zip=new JarOutputStream(bytes,manifest)) { zip.putNextEntry(new JarEntry(type.replace('.','/')+".class")); zip.write(new byte[]{1,2,3}); zip.closeEntry(); }
        return bytes.toByteArray();
    }
    private Jar artifact(String name,byte[] bytes) throws Exception { return new Jar("example.jdbc",name,"1.0",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),bytes.length); }
    private Driver driver(Release release) { return new Driver("fixture","Fixture",TYPE,"jdbc:fixture:","jdbc:fixture:test",URI.create("https://example.com"),"test",URI.create("https://example.com/license"),List.of(release),List.of("fixture")); }
    private Path target(Jar jar) { return temp.resolve("cache").resolve(jar.repositoryPath()); }
    private PreviewExecution.Cancellation cancel() { return new PreviewExecution.Cancellation(); }
    private void assertNoPartFiles() throws Exception { try(var files=Files.walk(temp)) { assertTrue(files.noneMatch(p -> p.toString().endsWith(".part"))); } }
    private static class Response extends HttpURLConnection {
        final byte[] body; int status=200; long length; final AtomicInteger disconnects=new AtomicInteger();
        Response(URI uri,byte[] body) throws Exception { super(uri.toURL());this.body=body;length=body.length; }
        @Override public void connect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { disconnects.incrementAndGet(); }
        @Override public int getResponseCode() { return status; }
        @Override public long getContentLengthLong() { return length; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
    }
    private JdbcDriverDownloads downloads(JdbcDriverDownloads.Transport transport) { return new JdbcDriverDownloads(temp.resolve("cache"),transport); }

    @Test void catalogContainsOnlyPinnedStableHttpsReleasesAndIncludesRequiredDependencies() {
        assertEquals(7,JdbcDriverCatalog.drivers().size()); Set<String> paths=new HashSet<>();
        for(var driver:JdbcDriverCatalog.drivers()) for(var release:driver.releases()) {
            assertFalse(release.version().contains("preview")); assertFalse(release.version().contains("SNAPSHOT"));
            for(var jar:release.jars()) { assertEquals("https",jar.uri().getScheme());assertEquals("repo.maven.apache.org",jar.uri().getHost());assertTrue(jar.sha256().matches("[a-f0-9]{64}"));paths.add(jar.repositoryPath()); }
        }
        assertTrue(paths.stream().anyMatch(p -> p.contains("checker-qual"))); assertTrue(paths.stream().anyMatch(p -> p.contains("protobuf-java")));
        assertEquals("sqlserver",JdbcDriverCatalog.infer("jdbc:sqlserver://private-server","oracle.jdbc.OracleDriver").id());
        assertEquals("oracle",JdbcDriverCatalog.infer("","oracle.jdbc.driver.OracleDriver").id());
    }
    @Test void downloadsAllJarsThenReusesVerifiedCacheWithoutNetwork() throws Exception {
        byte[] data=jar(TYPE),depBytes=jar("example.Dependency"); var main=artifact("fixture",data);var dep=artifact("dependency",depBytes);
        var release=new Release("1.0","",List.of(main,dep)); AtomicInteger calls=new AtomicInteger(); List<Response> responses=new ArrayList<>();
        var stable=downloads(uri -> {calls.incrementAndGet();try{var r=new Response(uri,uri.equals(main.uri())?data:depBytes);responses.add(r);return r;}catch(Exception e){throw new IOException(e);}});
        var first=stable.download(driver(release),release,cancel(),p -> {}); assertEquals(List.of(target(main),target(dep)),first); assertEquals(2,calls.get());
        List<JdbcDriverDownloads.Progress> progress=new ArrayList<>(); assertEquals(first,stable.download(driver(release),release,cancel(),progress::add));assertEquals(2,calls.get());assertTrue(progress.stream().allMatch(JdbcDriverDownloads.Progress::cached));
        assertTrue(responses.stream().allMatch(r -> r.getConnectTimeout()==10000 && r.getReadTimeout()==10000 && !r.getInstanceFollowRedirects()));
        assertTrue(responses.stream().allMatch(r -> r.getRequestProperty("Authorization")==null && r.getRequestProperty("Cookie")==null)); assertNoPartFiles();
    }
    @Test void cacheModificationTriggersValidationAndRedownload() throws Exception {
        byte[] data=jar(TYPE); var artifact=artifact("fixture",data); var release=new Release("1.0","",List.of(artifact));AtomicInteger requests=new AtomicInteger();
        var download=downloads(uri -> {requests.incrementAndGet();try{return new Response(uri,data);}catch(Exception e){throw new IOException(e);}});
        download.download(driver(release),release,cancel(),p -> {});byte[] wrong=data.clone();wrong[0]^=1;Files.write(target(artifact),wrong);
        download.download(driver(release),release,cancel(),p -> {});assertEquals(2,requests.get());assertArrayEquals(data,Files.readAllBytes(target(artifact)));assertNoPartFiles();
    }
    @Test void checksumFailureDoesNotReplaceAnExistingCacheFile() throws Exception {
        byte[] data=jar(TYPE);var artifact=artifact("fixture",data);var release=new Release("1.0","",List.of(artifact));
        byte[] wrong=data.clone();wrong[0]^=1;Files.createDirectories(target(artifact).getParent());Files.write(target(artifact),wrong);
        var download=downloads(uri -> {try{return new Response(uri,wrong);}catch(Exception e){throw new IOException(e);}});
        assertThrows(GenerationException.class,() -> download.download(driver(release),release,cancel(),p -> {}));assertArrayEquals(wrong,Files.readAllBytes(target(artifact)));assertNoPartFiles();
    }
    @Test void missingDriverClassAndInvalidJarAreRejectedEvenWhenTheHashMatches() throws Exception {
        for(byte[] data:List.of(jar("example.Other"),"not a JAR".getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            var artifact=artifact("fixture",data);var release=new Release("1.0","",List.of(artifact));
            var download=downloads(uri -> {try{return new Response(uri,data);}catch(Exception e){throw new IOException(e);}});
            assertThrows(GenerationException.class,() -> download.download(driver(release),release,cancel(),p -> {})); assertFalse(Files.exists(target(artifact)));
        }assertNoPartFiles();
    }
    @Test void httpErrorsAndRedirectsAreNotFollowedOrSelected() throws Exception {
        byte[] data=jar(TYPE);var artifact=artifact("fixture",data);var release=new Release("1.0","",List.of(artifact));
        for(int status:new int[]{302,403,404,500}) {
            var download=downloads(uri -> {try{var response=new Response(uri,data);response.status=status;return response;}catch(Exception e){throw new IOException(e);}});
            assertTrue(assertThrows(GenerationException.class,() -> download.download(driver(release),release,cancel(),p -> {})).getMessage().contains("HTTP "+status));assertFalse(Files.exists(target(artifact)));
        }assertNoPartFiles();
    }
    @Test void truncatedAndOversizedStreamsAreRejectedAndPartialFilesRemoved() throws Exception {
        byte[] expected=jar(TYPE);var artifact=artifact("fixture",expected);var release=new Release("1.0","",List.of(artifact));
        for(byte[] data:List.of(Arrays.copyOf(expected,expected.length-1),Arrays.copyOf(expected,expected.length+1))) {
            var download=downloads(uri -> {try{var r=new Response(uri,data);r.length=-1;return r;}catch(Exception e){throw new IOException(e);}});
            assertThrows(GenerationException.class,() -> download.download(driver(release),release,cancel(),p -> {}));assertFalse(Files.exists(target(artifact)));assertNoPartFiles();
        }
    }
    @Test void dependencyFailureReturnsNoPartialSelectionButCanKeepAnAlreadyVerifiedMainJar() throws Exception {
        byte[] mainBytes=jar(TYPE),depBytes=jar("example.Dep");var main=artifact("fixture",mainBytes);var dep=artifact("dep",depBytes);var release=new Release("1.0","",List.of(main,dep));
        var download=downloads(uri -> {try{var r=new Response(uri,mainBytes);if(uri.equals(dep.uri()))r.status=404;return r;}catch(Exception e){throw new IOException(e);}});
        assertThrows(GenerationException.class,() -> download.download(driver(release),release,cancel(),p -> {}));assertArrayEquals(mainBytes,Files.readAllBytes(target(main)));assertFalse(Files.exists(target(dep)));assertNoPartFiles();
    }
    @Test void canceledOperationsCloseTheConnectionAndNeverPublishAPartialJar() throws Exception {
        stalled(false);
    }
    @Test void timeoutClosesTheConnectionAndNeverPublishesAPartialJar() throws Exception {
        stalled(true);
    }
    private void stalled(boolean timeout) throws Exception {
        byte[] data=jar(TYPE);var artifact=artifact("fixture",data);var release=new Release("1.0","",List.of(artifact));CountDownLatch started=new CountDownLatch(1),closed=new CountDownLatch(1),finished=new CountDownLatch(1);AtomicBoolean canceled=new AtomicBoolean();
        var download=downloads(uri -> {try{return new Response(uri,data) {
            @Override public void disconnect(){super.disconnect();closed.countDown();}
            @Override public InputStream getInputStream(){return new InputStream(){@Override public int read()throws IOException{started.countDown();try{closed.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("interrupted");}return -1;}};}
        };}catch(Exception e){throw new IOException(e);}});
        try(var execution=new PreviewExecution(1)) {
            CompletableFuture<Throwable> result=CompletableFuture.supplyAsync(() -> {try{execution.run(cancel -> {try{return download.download(driver(release),release,cancel,p -> {});}finally{finished.countDown();}},canceled::get,Duration.ofMillis(timeout?150:5000));return null;}catch(Exception e){return e;}});
            assertTrue(started.await(2,TimeUnit.SECONDS));if(!timeout)canceled.set(true);
            Throwable error=result.get(2,TimeUnit.SECONDS);if(timeout)assertInstanceOf(TimeoutException.class,error);else assertInstanceOf(CancellationException.class,error);
            assertTrue(closed.await(2,TimeUnit.SECONDS));assertTrue(finished.await(2,TimeUnit.SECONDS));assertFalse(Files.exists(target(artifact)));assertNoPartFiles();
        }
    }
    @Test void keepsAuthenticationExtrasButReplacesKnownOldDriverAndDependencyVersions() {
        var db2=JdbcDriverCatalog.drivers().stream().filter(d -> d.id().equals("db2")).findFirst().orElseThrow();
        assertEquals(List.of(Path.of("new.jar"),Path.of("db2jcc_license_cisuz.jar")),JdbcDriverCatalog.classpath(db2,db2.releases().get(0),List.of(Path.of("new.jar")),"db2jcc4.jar;db2jcc_license_cisuz.jar",true));
        var mysql=JdbcDriverCatalog.drivers().stream().filter(d -> d.id().equals("mysql")).findFirst().orElseThrow();
        assertEquals(List.of(Path.of("new.jar"),Path.of("custom-auth.jar")),JdbcDriverCatalog.classpath(mysql,mysql.releases().get(0),List.of(Path.of("new.jar")),"mysql-connector-java-8.0.33.jar;protobuf-java-3.25.0.jar;custom-auth.jar",true));
        assertEquals(List.of(Path.of("new.jar")),JdbcDriverCatalog.classpath(mysql,mysql.releases().get(0),List.of(Path.of("new.jar")),"custom-auth.jar",false));
    }
    @Test void invalidCoordinatesAndSymlinkCachePathsCannotWriteOutsideTheCache() throws Exception {
        assertThrows(IllegalArgumentException.class,() -> new Jar("../x","fixture","1.0","0".repeat(64),1));
        byte[] data=jar(TYPE);var jar=artifact("fixture",data);var release=new Release("1.0","",List.of(jar));
        Path cache=temp.resolve("cache");Files.createDirectories(cache);Path outside=Files.createDirectory(temp.resolve("outside"));Files.createSymbolicLink(cache.resolve("example"),outside);
        assertThrows(GenerationException.class,() -> downloads(uri -> {throw new AssertionError("No network needed");}).download(driver(release),release,cancel(),p -> {}));
        try(var files=Files.list(outside)){assertEquals(0,files.count());}
    }
}
