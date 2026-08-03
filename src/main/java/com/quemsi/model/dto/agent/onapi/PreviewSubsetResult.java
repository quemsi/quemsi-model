package com.quemsi.model.dto.agent.onapi;

import java.util.ArrayList;
import java.util.List;

import com.quemsi.model.dto.agent.AgentCommandSync;
import com.quemsi.model.flow.subset.SubsetPlan.SubsetTableSummary;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class PreviewSubsetResult extends AgentCommandSync {
    private boolean success;
    private String message;
    private String errorMessage;
    private List<SubsetTableSummary> tables = new ArrayList<>();

    @Builder
    public PreviewSubsetResult(Long agentId, Long correlationId, long timeoutMilis,
            boolean success, String message, String errorMessage, List<SubsetTableSummary> tables) {
        super(PreviewSubsetResult.class.getSimpleName(), agentId, correlationId, timeoutMilis);
        this.success = success;
        this.message = message;
        this.errorMessage = errorMessage;
        this.tables = tables != null ? tables : new ArrayList<>();
    }
}
