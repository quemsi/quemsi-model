package com.quemsi.model.flow.in;

import com.quemsi.model.flow.db.sql.DbModel.DbTable;
import java.util.Map;
import lombok.Data;

@Data
public class TableDataPage {
    private Request request;
    private boolean hasMorePage;
    private Map<Object, Object[]> tableData;
    @Data
    public static class Request{
        public int pageNum;
        private DbTable table;
        private int pageSize;
    }
}
