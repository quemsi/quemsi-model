package com.quemsi.model.flow.db.sql;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DbSequence {
    private String name;
    private Long startValue;    
    private Long minValue;
    private Long maxValue;
    private Long incrementBy;
    private boolean cycle;
    private Long cacheSize;
    private Long lastValue;
}
