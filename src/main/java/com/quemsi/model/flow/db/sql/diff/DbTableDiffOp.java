package com.quemsi.model.flow.db.sql.diff;

import com.quemsi.model.flow.db.sql.DbTable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DbTableDiffOp implements DbModelDiffOp {
    private DiffOpType opType;
    private String qualifiedName;
    private DbTable oldTable;
    private DbTable newTable;

    @Override
    public DiffEntityType getEntityType() {
        return DiffEntityType.TABLE;
    }
}

