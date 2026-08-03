package com.quemsi.model.flow.subset;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
public class SubsetPlan {
    /** Qualified table name → selected primary-key strings (composite joined with PK separator). */
    @Builder.Default
    private Map<String, Set<String>> primaryKeysByTable = new LinkedHashMap<>();

    /** Qualified table name → how rows were included. */
    @Builder.Default
    private Map<String, SubsetTableProvenance> provenanceByTable = new LinkedHashMap<>();

    public Set<String> keysFor(String qualifiedTable) {
        return primaryKeysByTable.getOrDefault(qualifiedTable, Set.of());
    }

    public long rowCount(String qualifiedTable) {
        return keysFor(qualifiedTable).size();
    }

    public List<SubsetTableSummary> summaries() {
        List<SubsetTableSummary> list = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : primaryKeysByTable.entrySet()) {
            SubsetTableProvenance prov = provenanceByTable.getOrDefault(e.getKey(), SubsetTableProvenance.empty());
            list.add(SubsetTableSummary.builder()
                .table(e.getKey())
                .count(e.getValue().size())
                .driverCount(prov.getDriverCount())
                .requiredByFkCount(prov.getRequiredByFkCount())
                .requiredBy(List.copyOf(prov.getRequiredByTables()))
                .build());
        }
        return list;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubsetTableProvenance {
        @Builder.Default
        private long driverCount = 0;
        @Builder.Default
        private long requiredByFkCount = 0;
        @Builder.Default
        private Set<String> requiredByTables = new LinkedHashSet<>();

        public static SubsetTableProvenance empty() {
            return SubsetTableProvenance.builder().build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubsetTableSummary implements Serializable{
        private String table;
        private long count;
        private long driverCount;
        private long requiredByFkCount;
        private List<String> requiredBy;
    }
}
