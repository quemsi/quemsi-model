package com.quemsi.model.flow.upsert;

import java.util.List;

public interface UpsertRowSource {
    List<Object[]> loadRows(String qualifiedTableName);
}
