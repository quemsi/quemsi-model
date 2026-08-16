package com.quemsi.model.flow.subset;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubsetBrowseResult implements Serializable {
    @Builder.Default
    private List<String> columns = new ArrayList<>();
    @Builder.Default
    private List<BrowseRow> rows = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BrowseRow implements Serializable {
        /** Canonical PK key (same encoding as subset planner). */
        private String pkKey;
        /** Cell values aligned with {@link SubsetBrowseResult#columns}, as display strings. */
        @Builder.Default
        private List<String> values = new ArrayList<>();
    }
}
