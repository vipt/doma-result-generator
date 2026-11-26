/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;

/** Versioned vendor artifacts verified on 2026-11-26. JDBC libraries are downloaded, not bundled. */
public final class JdbcDriverCatalog {
    public static final URI CENTRAL = URI.create("https://repo.maven.apache.org/maven2/");
    private JdbcDriverCatalog() {}
    public record Jar(String group, String artifact, String version, String sha256, long bytes) {
        public Jar {
            if (!group.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*") || !artifact.matches("[A-Za-z0-9_-]+")
                    || !version.matches("[0-9][A-Za-z0-9_.-]*") || version.contains("..")
                    || !sha256.matches("[a-f0-9]{64}") || bytes <= 0 || bytes > 64L * 1024 * 1024) throw new IllegalArgumentException("Invalid JDBC catalog artifact.");
        }
        public String filename() { return artifact + "-" + version + ".jar"; }
        public String repositoryPath() { return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + filename(); }
        public URI uri() { return CENTRAL.resolve(repositoryPath()); }
    }
    public record Release(String version, String notes, List<Jar> jars) {
        public Release { jars = List.copyOf(jars); if (jars.isEmpty()) throw new IllegalArgumentException("A driver JAR is required."); }
        public long bytes() { return jars.stream().mapToLong(Jar::bytes).sum(); }
        public String label() { return version + (notes.isBlank() ? "" : " — " + notes); }
    }
    public record Driver(String id, String name, String driverClass, String urlPrefix, String urlExample,
            URI website, String license, URI licenseUrl, List<Release> releases, List<String> jarPrefixes) {
        public Driver { releases = List.copyOf(releases); jarPrefixes = List.copyOf(jarPrefixes); }
    }
    private static Jar jar(String path, String version, String sha, long bytes) {
        int slash = path.lastIndexOf('/');
        return new Jar(path.substring(0, slash).replace('/', '.'), path.substring(slash + 1), version, sha, bytes);
    }
    private static Release release(String version, String notes, Jar... jars) { return new Release(version, notes, List.of(jars)); }
    private static Driver driver(String id, String name, String type, String prefix, String example, String website,
            String license, String licenseUrl, List<String> jarPrefixes, Release... releases) {
        return new Driver(id, name, type, prefix, example, URI.create(website), license, URI.create(licenseUrl), List.of(releases), jarPrefixes);
    }
    private static final List<Driver> DRIVERS = List.of(
        driver("oracle", "Oracle", "oracle.jdbc.OracleDriver", "jdbc:oracle:", "jdbc:oracle:thin:@//<host>:1521/<service>",
            "https://www.oracle.com/database/technologies/appdev/jdbc-downloads.html", "Oracle Free Use Terms and Conditions",
            "https://www.oracle.com/downloads/licenses/oracle-free-license.html", List.of("ojdbc"),
            release("23.26.4.0.0", "ojdbc11; Java 11+",
                jar("com/oracle/database/jdbc/ojdbc11", "23.26.4.0.0", "df977e9291499cd4ceee7d577462c285987dd2ad0216637c0865eb98956a9e6e", 8828929))),
        driver("sqlserver", "SQL Server", "com.microsoft.sqlserver.jdbc.SQLServerDriver", "jdbc:sqlserver:", "jdbc:sqlserver://<host>:1433;databaseName=<database>",
            "https://learn.microsoft.com/en-us/sql/connect/jdbc/download-microsoft-jdbc-driver-for-sql-server", "MIT",
            "https://github.com/microsoft/mssql-jdbc/blob/main/LICENSE", List.of("mssql-jdbc", "sqljdbc"),
            release("13.6.2.jre11", "Java 11+",
                jar("com/microsoft/sqlserver/mssql-jdbc", "13.6.2.jre11", "5fbc0a70fe4d1d563d431a41b23f493d68807571f0bb49db0bb1737e83e9161f", 1585109))),
        driver("postgresql", "PostgreSQL", "org.postgresql.Driver", "jdbc:postgresql:", "jdbc:postgresql://<host>:5432/<database>",
            "https://jdbc.postgresql.org/download/", "BSD-2-Clause", "https://jdbc.postgresql.org/about/license.html", List.of("postgresql"),
            release("42.7.15", "Java 8+; includes checker-qual",
                jar("org/postgresql/postgresql", "42.7.15", "9776fca62dfc84f312d5c1aff9791f6f3cf56d518a8eca4a3a8dca7a95ef8881", 1223422),
                jar("org/checkerframework/checker-qual", "3.55.1", "857658c8bdcbff794949a8d9922f88d63ee3751c203c7cd7d21ea97f9e745288", 241885))),
        driver("mysql", "MySQL", "com.mysql.cj.jdbc.Driver", "jdbc:mysql:", "jdbc:mysql://<host>:3306/<database>",
            "https://dev.mysql.com/doc/connector-j/en/", "GPLv2 with Universal FOSS Exception",
            "https://dev.mysql.com/doc/connector-j/en/preface.html", List.of("mysql-connector-j", "mysql-connector-java"),
            release("26.10.0", "MySQL 8.4+; includes protobuf-java",
                jar("com/mysql/mysql-connector-j", "26.10.0", "cef70caf532833c9ab8f41f74773ce22c08bf503fad215c9c2ee1e9db87e759e", 2618793),
                jar("com/google/protobuf/protobuf-java", "4.36.1", "26434a93561a1a44bf7157b2630fa73a5b4d8df20e94df167bc03fcd14abd7e4", 1795060)),
            release("9.7.0", "MySQL 8.0+; includes protobuf-java",
                jar("com/mysql/mysql-connector-j", "9.7.0", "0353648eaa1c91e0f4020c959abf756bc866ffd583df22ae6b6f6e0cbd43eb44", 2607237),
                jar("com/google/protobuf/protobuf-java", "4.31.1", "d60dfe7c68a0d38a248cca96924f289dc7e1966a887ee7cae397701af08575ae", 1873183))),
        driver("mariadb", "MariaDB", "org.mariadb.jdbc.Driver", "jdbc:mariadb:", "jdbc:mariadb://<host>:3306/<database>",
            "https://mariadb.org/connector-java/all-releases/", "LGPL-2.1-or-later", "https://spdx.org/licenses/LGPL-2.1-or-later.html", List.of("mariadb-java-client"),
            release("4.0.0", "Java 17+; TLS verify-full by default",
                jar("org/mariadb/jdbc/mariadb-java-client", "4.0.0", "bb1211dac59ef4c54b7a8ad4755a913d36d2e81b34ca1d4cb9ffa9d5633df01f", 817345)),
            release("3.5.11", "3.x branch; Java 8+",
                jar("org/mariadb/jdbc/mariadb-java-client", "3.5.11", "42604178b9e57ec08ee0035079961516f086e455d0141f0b22c6eff29fb2fff4", 793387))),
        driver("db2", "Db2 LUW", "com.ibm.db2.jcc.DB2Driver", "jdbc:db2:", "jdbc:db2://<host>:50000/<database>",
            "https://www.ibm.com/products/db2/drivers", "IBM International Program License Agreement",
            "https://www.ibm.com/support/customer/csol/terms/?ref=L-HJHH-A2W6TH-01-06-2026-zz-en", List.of("jcc", "db2jcc"),
            release("12.1.5.0", "Db2 LUW; z/OS/i may require a separate license JAR",
                jar("com/ibm/db2/jcc", "12.1.5.0", "04150111a29370247d162c3f05b8e938bf1314f19a63ad9a5302d88dd478e8e8", 6630167))),
        driver("h2", "H2", "org.h2.Driver", "jdbc:h2:", "jdbc:h2:tcp://<host>/<database>",
            "https://h2database.com/html/download.html", "MPL-2.0 or EPL-1.0", "https://github.com/h2database/h2database/blob/master/LICENSE.txt", List.of("h2-"),
            release("2.5.252", "H2 2.x",
                jar("com/h2database/h2", "2.5.252", "90b11dc413da82070ef15fc943282c1408e6ffd34fa68998335fea8603b5ff20", 2693360)))
    );
    public static List<Driver> drivers() { return DRIVERS; }
    public static Driver infer(String jdbcUrl, String driverClass) {
        String url = jdbcUrl.toLowerCase(Locale.ROOT);
        return DRIVERS.stream().filter(d -> url.startsWith(d.urlPrefix())).findFirst()
                .orElseGet(() -> DRIVERS.stream().filter(d -> d.driverClass().equals(driverClass)
                        || d.id().equals("oracle") && driverClass.startsWith("oracle.jdbc.")).findFirst().orElse(DRIVERS.get(0)));
    }
    /** New verified JARs take precedence; known older copies are replaced while auth/wallet extras may be retained. */
    public static List<Path> classpath(Driver driver, Release release, List<Path> downloaded, String current, boolean keepExtras) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>(downloaded);
        if (keepExtras) for (String text : current.split(";")) {
            if (text.isBlank()) continue;
            Path path = Path.of(text.trim()); String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean replaced = driver.jarPrefixes().stream().anyMatch(prefix -> primaryJarName(prefix, name))
                    || release.jars().stream().anyMatch(j -> primaryJarName(j.artifact().toLowerCase(Locale.ROOT), name));
            if (!replaced) paths.add(path);
        }
        return List.copyOf(paths);
    }
    private static boolean primaryJarName(String prefix, String name) {
        if (prefix.equals("ojdbc")) return name.matches("ojdbc[0-9]+(?:_g)?(?:-[0-9].*)?\\.jar");
        if (prefix.equals("db2jcc")) return name.matches("db2jcc4?(?:-[0-9].*)?\\.jar");
        if (prefix.equals("sqljdbc")) return name.matches("sqljdbc[0-9]*(?:-[0-9].*)?\\.jar");
        if (prefix.endsWith("-")) return name.startsWith(prefix) && name.length() > prefix.length() && Character.isDigit(name.charAt(prefix.length()));
        return name.equals(prefix + ".jar") || name.startsWith(prefix + "-") && name.length() > prefix.length() + 1 && Character.isDigit(name.charAt(prefix.length() + 1));
    }
    // Dependency versions are pinned per release; preview does not resolve Maven graphs dynamically.
}
