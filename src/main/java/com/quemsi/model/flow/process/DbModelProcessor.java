package com.quemsi.model.flow.process;

import com.quemsi.model.flow.db.sql.DbModel;

public interface DbModelProcessor {
    void process(DbModel dbModel);
}
