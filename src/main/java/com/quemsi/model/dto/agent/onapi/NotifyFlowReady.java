package com.quemsi.model.dto.agent.onapi;

import com.quemsi.model.dto.agent.AgentCommand;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class NotifyFlowReady extends AgentCommand {
    private String flowName;

    @Builder
    public NotifyFlowReady(Long agentId, String flowName) {
        super(NotifyFlowReady.class.getSimpleName(), agentId);
        this.flowName = flowName;
    }
}
