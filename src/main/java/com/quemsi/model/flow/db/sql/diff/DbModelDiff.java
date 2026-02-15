package com.quemsi.model.flow.db.sql.diff;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class DbModelDiff {
    private List<DbModelDiffOp> operations = new ArrayList<>();
}

