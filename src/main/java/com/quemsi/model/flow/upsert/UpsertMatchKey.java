package com.quemsi.model.flow.upsert;

import java.util.List;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class UpsertMatchKey {
    List<String> columns;
    boolean primaryKey;
    String source;
}
