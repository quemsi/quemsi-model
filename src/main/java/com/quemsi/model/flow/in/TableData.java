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
    public static final String FORMAT_TABULAR = "tabular";
    public static final String FORMAT_DOCUMENT = "document";

    private String tableName;
    private Integer pageSize;
    private Integer totalPages;
    private Integer totalRecords;
    /** tabular (default for RDBMS) or document (MongoDB). */
    private String dataFormat = FORMAT_TABULAR;
    private List<DataPage> dataPages = new CopyOnWriteArrayList<>();
    
    public TableData(String tableName){
        this.tableName = tableName;
    }

    public boolean isDocumentFormat(){
        return FORMAT_DOCUMENT.equals(dataFormat);
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    public static class DataPage {
        private int pageNum;
        private Map<Object, Object[]> data;
        /** Used when TableData.dataFormat is document: _id → document map. */
        private Map<Object, Map<String, Object>> documents;

        public DataPage(int pageNum, Map<Object, Object[]> data){
            this.pageNum = pageNum;
            this.data = data;
        }

        public int getSize(){
            if(documents != null){
                return documents.size();
            }
            return data == null ? 0 : data.size();
        }
    }
}
