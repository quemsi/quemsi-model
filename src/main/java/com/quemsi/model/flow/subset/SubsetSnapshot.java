package com.quemsi.model.flow.subset;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.quemsi.model.flow.subset.SubsetPlan.SubsetTableSummary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Snapshot of subset definition and resulting per-table counts for a backup version
 * (and {@code subset-snapshot.json} in the archive).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubsetSnapshot implements Serializable{
    private SubsetConfig config;
    @Builder.Default
    private List<SubsetTableSummary> tables = new ArrayList<>();
}
