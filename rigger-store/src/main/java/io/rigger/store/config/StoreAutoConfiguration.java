package io.rigger.store.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * Auto-configuration for the Rigger store.
 *
 * <p>Both the datasource and the Hibernate dialect are picked by beans conditional on
 * {@code rigger.store.type} (sqlite, the default, or postgresql) — a scan of the environment at
 * startup, not a Spring profile. Deliberately not profile-based: a profile would mean a second
 * {@code application-*.yaml} to keep in sync with the base config, where a conditional bean here
 * is the exact same mechanism the SQLite datasource already used, just extended to a second value.
 */
@AutoConfiguration
@EnableJpaRepositories(basePackages = "io.rigger.store.repository")
@EntityScan(basePackages = "io.rigger.store.entity")
@EnableTransactionManagement
@ComponentScan(basePackages = "io.rigger.store")
public class StoreAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "rigger.store.type", havingValue = "sqlite", matchIfMissing = true)
    @ConditionalOnMissingBean(DataSource.class)
    public DataSource sqliteDataSource(StoreProperties props) {
        var config = new SQLiteConfig();
        // Store TEXT-typed timestamp columns as formatted date strings, not raw epoch millis —
        // Hibernate's Instant mapping round-trips through java.sql.Timestamp, and sqlite-jdbc's
        // default (INTEGER/millis) can't be parsed back by its own getTimestamp() when the
        // Flyway schema declares the column as TEXT.
        config.setDateClass("TEXT");
        config.setDateStringFormat("yyyy-MM-dd HH:mm:ss.SSS");

        // Wait for a held write lock instead of failing immediately. SQLite allows one writer at a
        // time, and without this a concurrent write throws SQLITE_BUSY straight away
        // (CannotAcquireLockException). That was unreachable while the scheduler had a single thread
        // and every job took its turn; raising `spring.task.scheduling.pool.size` so the jobs stop
        // delaying each other is precisely what made it reachable, and it was observed once on an
        // insert into metric_samples while the reconciliation loop was writing. Five seconds is far
        // longer than any write here takes, so it costs nothing in the normal case.
        config.setBusyTimeout(5000);

        var ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + props.getPath());
        // WAL mode: allows concurrent reads while a write is in progress
        ds.setJournalMode("WAL");
        return ds;
    }

    /**
     * Pooled Postgres datasource. Unlike SQLite (a single file, one writer at a time, no pool),
     * Postgres is a real server and benefits from connection pooling — Hikari is already on the
     * classpath transitively via {@code spring-boot-starter-data-jpa}, so this needs nothing extra
     * beyond the driver itself.
     */
    @Bean
    @ConditionalOnProperty(name = "rigger.store.type", havingValue = "postgresql")
    @ConditionalOnMissingBean(DataSource.class)
    public DataSource postgresDataSource(StoreProperties props) {
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://" + props.getHost() + ":" + props.getPort()
            + "/" + props.getDatabase());
        config.setUsername(props.getUsername());
        config.setPassword(props.getPassword());
        config.setDriverClassName("org.postgresql.Driver");
        return new HikariDataSource(config);
    }

    /**
     * SQLite's dialect lives in {@code hibernate-community-dialects} and is never auto-detected
     * from the JDBC connection — it has to be named explicitly. Postgres needs no equivalent
     * customizer: {@code PostgreSQLDialect} ships in {@code hibernate-core} and Hibernate resolves
     * it on its own from the live connection's product name.
     */
    @Bean
    @ConditionalOnProperty(name = "rigger.store.type", havingValue = "sqlite", matchIfMissing = true)
    public HibernatePropertiesCustomizer sqliteDialectCustomizer() {
        return props -> props.put("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
    }
}