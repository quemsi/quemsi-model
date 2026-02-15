package com.quemsi.model.flow.db.sql.diff;

import com.quemsi.model.flow.db.sql.DbModel.CheckConstraint;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DbCheckConstraintDiffOp implements DbModelDiffOp {
    private DiffOpType opType;
    private String qualifiedName;
    private CheckConstraint oldConstraint;
    private CheckConstraint newConstraint;

    @Override
    public DiffEntityType getEntityType() {
        return DiffEntityType.CHECK_CONSTRAINT;
    }
}

