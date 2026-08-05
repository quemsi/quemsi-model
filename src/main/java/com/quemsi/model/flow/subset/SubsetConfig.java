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
public class SubsetConfig implements Serializable{
    public static final int DEFAULT_MAX_ROWS_PER_TABLE = 100_000;

    private boolean enabled;
    @Builder.Default
    private List<SubsetDriver> drivers = new ArrayList<>();
    /** Fail planning when any table would exceed this many rows. */
    @Builder.Default
    private int maxRowsPerTable = DEFAULT_MAX_ROWS_PER_TABLE;

    public boolean isActive() {
        return enabled && drivers != null && !drivers.isEmpty();
    }
}
