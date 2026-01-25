package com.quemsi.model.dto;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaUpdateConfig implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * If true, log SQL statements without executing them
     */
    @Builder.Default
    private Boolean dryRun = false;
    
    /**
     * If true, continue executing remaining statements when an error occurs
     */
    @Builder.Default
    private Boolean continueOnError = true;
    
    /**
     * If true, skip sequence operations in the migration
     */
    @Builder.Default
    private Boolean skipSequences = false;
}

