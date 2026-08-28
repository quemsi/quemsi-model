package com.quemsi.model.flow.upsert;

import java.util.ArrayList;
import java.util.List;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class UpsertPlan {
    List<UpsertTablePlan> tables;
    @Builder.Default
    List<UpsertFailure> failures = new ArrayList<>();

    public boolean isUpsertable() {
        return failures == null || failures.isEmpty();
    }

    public UpsertTablePlan tablePlan(String qualifiedName) {
        if (tables == null) {
            return null;
        }
        for (UpsertTablePlan plan : tables) {
            if (plan.getQualifiedName().equals(qualifiedName)) {
                return plan;
            }
        }
        return null;
    }
}
