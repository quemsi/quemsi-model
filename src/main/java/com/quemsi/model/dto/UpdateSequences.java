package com.quemsi.model.dto;

import java.io.Serializable;
import java.util.List;

import lombok.Data;

@Data
public class UpdateSequences implements Serializable {
    private String sequenceNameTemplate;
    private String columnName;
    private List<SequenceMapping> customMappings;
    
    @Data
    public static class SequenceMapping implements Serializable {
        private String sequence;
        private String schema;
        private String table;
        private String column;   
    }
}

