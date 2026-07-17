package com.quemsi.model.flow.db.sql.diff;

import com.quemsi.model.flow.db.sql.DbView;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DbViewDiffOp implements DbModelDiffOp {
    private DiffOpType opType;
    private String qualifiedName;
    private DbView oldView;
    private DbView newView;

    @Override
    public DiffEntityType getEntityType() {
        return DiffEntityType.VIEW;
    }
}
