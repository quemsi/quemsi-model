package com.quemsi.model.dto;

import java.io.Serializable;
import java.util.List;

import lombok.Data;

@Data
public class MaskColumn implements Serializable {
    private MaskType maskType;
    private String maskChar;
    private int length;
    private int parallelism;
    private List<MaskColumnConfig> columns;
    @Data
    public static class MaskColumnConfig {
        private String schema;
        private String table;
        private String column;   
    }
}
