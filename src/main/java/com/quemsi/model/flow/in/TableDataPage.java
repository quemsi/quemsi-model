package com.quemsi.model.flow.in;

import java.util.Map;

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
    
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder(toBuilder = true)
    @Data
    public static class Request{
        public int pageNum;
        private DbTable table;
        private int pageSize;
    }
}
