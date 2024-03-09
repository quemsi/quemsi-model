package com.biddflux.model.dto.agent;

import java.util.Map;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class ExecuteFlow extends AgentCommand{
    private String flowName;
    private Long versionId;
    private Map<String, String> tags;
    
    @Builder
    public ExecuteFlow(Long agentId, String flowName, Long versionId, Map<String, String> tags){
        super(ExecuteFlow.class.getSimpleName(), agentId);
        this.flowName = flowName;
        this.versionId = versionId;
        this.tags = tags;
    }
}
