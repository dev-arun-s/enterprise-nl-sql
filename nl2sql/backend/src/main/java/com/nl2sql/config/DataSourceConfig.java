package com.nl2sql.config;

import oracle.jdbc.pool.OracleDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Dual DataSource configuration:
 *  - "oracleDataSource"  → Oracle DB (metadata extraction + SQL execution)
 *                          Built directly with OracleDataSource to expose
 *                          all Oracle-specific connection properties.
 *  - "h2DataSource"      → H2 in-memory DB (query history)
 */
@Configuration
public class DataSourceConfig {

    // ── Oracle ───────────────────────────────────────────────────────────────

    @Bean
    @ConfigurationProperties("spring.datasource.oracle")
    public DataSourceProperties oracleDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * Oracle DataSource built with native OracleDataSource so we can set
     * Oracle-specific connection properties that Spring Boot's generic
     * DataSourceBuilder does not expose.
     *
     * Key settings and why they help:
     *
     *  defaultRowPrefetch (fetch size)
     *      Oracle JDBC default is 10 rows per network round-trip.
     *      5000 means the driver buffers 5000 rows before going back to the
     *      server — reducing round-trips for 32k-row result sets from ~3200 to ~7.
     *
     *  defaultBatchValue
     *      Number of rows batched in a single network write for INSERT/UPDATE.
     *      Matters when executing DML; has no effect on SELECT.
     *
     *  useFetchSizeWithLongColumn
     *      Allows prefetch to work even when LONG/LONG RAW columns are present
     *      (DATA_DEFAULT in ALL_TAB_COLUMNS is a LONG column).
     *      Without this Oracle resets the fetch size to 1 whenever it encounters
     *      a LONG column, nullifying the prefetch setting entirely.
     *
     *  oracle.jdbc.implicitStatementCacheSize
     *      Caches PreparedStatement objects on the connection.
     *      Our extraction runs the same 6 queries potentially many times
     *      (if re-extraction is triggered). Cached statements skip re-parsing.
     *
     *  oracle.net.CONNECT_TIMEOUT / READ_TIMEOUT
     *      Prevents the app hanging indefinitely if the Oracle host is
     *      unreachable or a query hangs. Values in milliseconds.
     *
     *  TCP_NODELAY
     *      Disables Nagle's algorithm. For many small JDBC protocol packets
     *      this reduces latency. For bulk reads it has minimal effect but
     *      no downside.
     *
     *  Connection pool (HikariCP via Spring Boot auto-config):
     *      minimumIdle / maximumPoolSize set via application.yml under
     *      spring.datasource.oracle.hikari.*
     */
    @Bean(name = "oracleDataSource")
    public DataSource oracleDataSource(
            @Qualifier("oracleDataSourceProperties") DataSourceProperties props)
            throws SQLException {

        OracleDataSource ds = new OracleDataSource();
        ds.setURL(props.getUrl());
        ds.setUser(props.getUsername());
        ds.setPassword(props.getPassword());

        Properties connProps = new Properties();

        // ── Fetch / prefetch ─────────────────────────────────────────────────
        // Number of rows the driver buffers per server round-trip (default: 10)
        connProps.setProperty("defaultRowPrefetch", "5000");

        // Critical: allows prefetch even when LONG columns (e.g. DATA_DEFAULT)
        // are present. Without this Oracle silently resets fetch size to 1.
        connProps.setProperty("useFetchSizeWithLongColumn", "true");

        // ── Statement cache ───────────────────────────────────────────────────
        // Cache up to 20 PreparedStatements per connection — avoids re-parsing
        // the same extraction SQL on repeated extractions.
        connProps.setProperty("oracle.jdbc.implicitStatementCacheSize", "20");
        connProps.setProperty("oracle.jdbc.maxCachedBufferSize", "524288"); // 512 KB

        // ── DML batch size ────────────────────────────────────────────────────
        connProps.setProperty("defaultBatchValue", "100");

        // ── Network timeouts (milliseconds) ───────────────────────────────────
        // Fail fast if Oracle is unreachable rather than hanging the app startup
        connProps.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");  // 10s connect
        connProps.setProperty("oracle.jdbc.ReadTimeout",    "300000"); // 5 min read

        // ── TCP settings ──────────────────────────────────────────────────────
        connProps.setProperty("oracle.net.disableOob", "true"); // disable out-of-band breaks (more stable)
        connProps.setProperty("TCP_NODELAY", "true");            // disable Nagle algorithm

        // ── Result set type ───────────────────────────────────────────────────
        // Forward-only, read-only cursors are faster than scrollable/updatable
        connProps.setProperty("oracle.jdbc.defaultRowPrefetchSize", "5000");

        ds.setConnectionProperties(connProps);
        return ds;
    }

    @Bean(name = "oracleJdbcTemplate")
    public JdbcTemplate oracleJdbcTemplate(@Qualifier("oracleDataSource") DataSource ds) {
        JdbcTemplate template = new JdbcTemplate(ds);
        // Default fetch size on the template level — individual queries in
        // MetadataExtractionService override this via withFetchSize() as needed
        template.setFetchSize(5000);
        // Optimise for large result sets — sets ResultSet.TYPE_FORWARD_ONLY
        // and ResultSet.CONCUR_READ_ONLY on every statement
        template.setQueryTimeout(300); // 5 minute query timeout
        return template;
    }

    // ── H2 ──────────────────────────────────────────────────────────────────

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.h2")
    public DataSourceProperties h2DataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "h2DataSource")
    @Primary
    public DataSource h2DataSource() {
        return h2DataSourceProperties()
                .initializeDataSourceBuilder()
                .build();
    }

    @Bean(name = "h2JdbcTemplate")
    @Primary
    public JdbcTemplate h2JdbcTemplate(@Qualifier("h2DataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
