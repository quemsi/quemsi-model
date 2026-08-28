package com.quemsi.model.flow.upsert;

import java.util.ArrayList;
import java.util.List;

import com.quemsi.model.flow.db.sql.DbTable;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class UpsertTablePlan {
    String qualifiedName;
    DbTable table;
    UpsertMatchKey matchKey;
    @Builder.Default
    List<String> omitColumns = new ArrayList<>();
    @Builder.Default
    List<UpsertRow> inserts = new ArrayList<>();
    @Builder.Default
    List<UpsertRow> updates = new ArrayList<>();
    @Builder.Default
    List<UpsertRow> skips = new ArrayList<>();
}
