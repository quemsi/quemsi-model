package com.quemsi.model.dto.agent;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class DelayAgentCommand extends AgentCommand{
    private long delay;
    
    @Builder
    public DelayAgentCommand(Long agentId, long delay){
        super(DelayAgentCommand.class.getSimpleName(), agentId);
        this.delay = delay;
    }
}
