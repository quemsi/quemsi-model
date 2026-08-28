package com.quemsi.model.flow.upsert;

import lombok.Value;

@Value
public class UpsertRow {
    String key;
    Object[] values;
}
