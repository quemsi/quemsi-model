package com.quemsi.model.flow.upsert;

import lombok.Value;

@Value
public class UpsertFailure {
    String table;
    String key;
    String reason;
}
