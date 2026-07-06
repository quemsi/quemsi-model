package com.quemsi.model.flow.in;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@Data
public class TableData {
    private String tableName;
    private Integer pageSize;
    private Integer totalPages;
    private Integer totalRecords;
    private List<DataPage> dataPages = new CopyOnWriteArrayList<>();
    
    public TableData(String tableName){
        this.tableName = tableName;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    public static class DataPage {
        private int pageNum;
        private Map<Object, Object[]> data;
        public int getSize(){
            return data == null ? 0 : data.size();
        }
    }
}
