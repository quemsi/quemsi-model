package com.quemsi.model.flow.db.sql.diff;

import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DbForeignKeyDiffOp implements DbModelDiffOp {
    private DiffOpType opType;
    private String qualifiedName;
    private ReferenceInfo oldReference;
    private ReferenceInfo newReference;

    @Override
    public DiffEntityType getEntityType() {
        return DiffEntityType.FOREIGN_KEY;
    }
}

