package com.quemsi.model.flow.in;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.quemsi.model.flow.db.sql.DbTable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
public class TableDataPage {
    private Request request;
    private boolean hasMorePage;
    private Map<Object, Object[]> tableData;
    /** Used for MongoDB document pages (_id → document). */
    private Map<Object, Map<String, Object>> documents;
    
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder(toBuilder = true)
    @Data
    public static class Request{
        private AtomicLong seqGenerator;
        public int pageNum;
        private DbTable table;
        private int pageSize;
        /**
         * When non-null/non-empty, fetch only these primary-key strings (subset export page).
         * Composite PKs use {@link com.quemsi.model.flow.db.DataSourceFactory#PK_VALUES_SEPERATOR}.
         */
        private List<String> primaryKeys;
    }
}
