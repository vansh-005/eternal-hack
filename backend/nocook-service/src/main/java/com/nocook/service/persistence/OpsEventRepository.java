package com.nocook.service.persistence;

import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code nocook.ops_events}: one row per chaos action and per run marker (DATA_MODEL.md section 2). */
@Repository
public class OpsEventRepository {

    private final JdbcClient jdbc;

    public OpsEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String kind, Instant at, String replica, String metadataJson) {
        jdbc.sql("""
                        insert into nocook.ops_events (kind, at, replica, metadata)
                        values (:kind, :at, :replica, cast(:metadata as jsonb))""")
                .param("kind", kind)
                .param("at", CaseRepository.utc(at))
                .param("replica", replica)
                .param("metadata", metadataJson)
                .update();
    }
}
