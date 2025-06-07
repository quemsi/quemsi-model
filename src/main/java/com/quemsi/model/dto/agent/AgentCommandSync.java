package com.quemsi.model.dto.agent;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class AgentCommandSync extends AgentCommand{
    private Long correlationId;
    private long timeoutMilis = 2000;
    public AgentCommandSync(String name, Long agentId, Long correlationId, long timeoutMilis){
        super(name, agentId);
        this.correlationId = correlationId;
        this.timeoutMilis = timeoutMilis;
    }
}
