package io.rigger.store.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * Activates SQLite datasource when rigger.store.type=sqlite (the default).
 * When rigger.store.type=postgresql, Spring Boot's own datasource auto-config takes over.
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
}