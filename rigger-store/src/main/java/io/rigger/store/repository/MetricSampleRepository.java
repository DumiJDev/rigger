package io.rigger.store.repository;

import io.rigger.store.entity.MetricSampleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

/** Repository for sampled metric time series. */
@Repository
public interface MetricSampleRepository extends JpaRepository<MetricSampleEntity, String> {

    /**
     * One series over a window, oldest first — the order a chart plots left to right, so callers
     * do not have to reverse it.
     */
    @Query("""
        SELECT s FROM MetricSampleEntity s
        WHERE s.metric = :metric AND s.namespace = :namespace AND s.name = :name
          AND s.sampledAt >= :since
        ORDER BY s.sampledAt ASC
        """)
    List<MetricSampleEntity> series(@Param("metric") String metric,
                                    @Param("namespace") String namespace,
                                    @Param("name") String name,
                                    @Param("since") Instant since);

    /** Distinct {@code name} values recorded for a metric in a namespace, for charts that plot every series at once. */
    @Query("""
        SELECT DISTINCT s.name FROM MetricSampleEntity s
        WHERE s.metric = :metric AND s.namespace = :namespace AND s.sampledAt >= :since
        ORDER BY s.name ASC
        """)
    List<String> namesFor(@Param("metric") String metric,
                          @Param("namespace") String namespace,
                          @Param("since") Instant since);

    /**
     * Prunes samples older than the cutoff. Without this the table grows without bound at one row
     * per series per sampling interval, forever.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM MetricSampleEntity s WHERE s.sampledAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
