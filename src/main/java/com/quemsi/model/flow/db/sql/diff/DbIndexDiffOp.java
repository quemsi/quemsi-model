package com.quemsi.model.flow.db.sql.diff;

import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DbIndexDiffOp implements DbModelDiffOp {
    private DiffOpType opType;
    private String qualifiedName;
    private String tableQualifiedName;
    private IndexInfo oldIndex;
    private IndexInfo newIndex;

    @Override
    public DiffEntityType getEntityType() {
        return DiffEntityType.INDEX;
    }
}

