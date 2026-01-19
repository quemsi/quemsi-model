package com.quemsi.model.flow.db.sql.diff;

import com.quemsi.model.flow.db.sql.DbSequence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DbSequenceDiffOp implements DbModelDiffOp {
    private DiffOpType opType;
    private String qualifiedName;
    private DbSequence oldSequence;
    private DbSequence newSequence;

    @Override
    public DiffEntityType getEntityType() {
        return DiffEntityType.SEQUENCE;
    }
}

