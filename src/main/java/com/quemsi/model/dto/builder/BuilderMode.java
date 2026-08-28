package com.quemsi.model.dto.builder;

public enum BuilderMode {
    CLEAR_TABLES,
    DROP_TABLES,
    MASK_COLUMNS,
    UPDATE_SEQUENCES,
    SUBSET,
    /** Allowlist of backup tables to promote (from From source, not Upsert target). */
    UPSERT,
    /** Read-only schema / sample-row peek on the agent (no result_config). */
    BROWSE
}
