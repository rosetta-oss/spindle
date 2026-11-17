# drivers/

Put JDBC driver JARs here that Spindle does not bundle (licensing or vendor policy).
The launcher adds `drivers/*.jar` to the classpath; each connection names its `:driver` class.

| Database | Driver | Bundled? | Driver class |
|---|---|---|---|
| PostgreSQL | pgjdbc (BSD-2) | yes | `org.postgresql.Driver` |
| ClickHouse | clickhouse-jdbc (Apache-2.0) | yes | `com.clickhouse.jdbc.ClickHouseDriver` |
| MySQL | MySQL Connector/J (GPL-2.0 + FOSS exception) or MariaDB Connector/J (LGPL-2.1) | **no**: put it here | `com.mysql.cj.jdbc.Driver` / `org.mariadb.jdbc.Driver` |
| Sybase ASE | SAP jConnect 16 (proprietary) | **no**: put `jconn4.jar` here | `com.sybase.jdbc4.jdbc.SybDriver` |

JAR files in this directory are git-ignored. Never commit them.
