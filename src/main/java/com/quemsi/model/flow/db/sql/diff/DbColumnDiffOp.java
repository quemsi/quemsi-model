package com.quemsi.model.flow.db.sql.diff;

import com.quemsi.model.flow.db.sql.DbColumn;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DbColumnDiffOp implements DbModelDiffOp {
    private DiffOpType opType;
    private String qualifiedName;
    private String tableQualifiedName;
    private String columnName;
    private DbColumn oldColumn;
    private DbColumn newColumn;

    @Override
    public DiffEntityType getEntityType() {
        return DiffEntityType.COLUMN;
    }
}

