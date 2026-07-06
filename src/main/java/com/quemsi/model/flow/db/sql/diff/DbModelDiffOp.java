package com.quemsi.model.flow.db.sql.diff;

public interface DbModelDiffOp {
    DiffOpType getOpType();
    DiffEntityType getEntityType();
    String getQualifiedName();
}

