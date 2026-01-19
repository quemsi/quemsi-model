package com.quemsi.model.flow.db.sql.diff;

import com.quemsi.model.flow.db.sql.DbModel.ContraintInfo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DbUniqueConstraintDiffOp implements DbModelDiffOp {
    private DiffOpType opType;
    private String qualifiedName;
    private ContraintInfo oldConstraint;
    private ContraintInfo newConstraint;

    @Override
    public DiffEntityType getEntityType() {
        return DiffEntityType.UNIQUE_CONSTRAINT;
    }
}

