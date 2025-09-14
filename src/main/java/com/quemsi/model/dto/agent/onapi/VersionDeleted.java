package com.quemsi.model.dto.agent.onapi;

import com.quemsi.model.dto.agent.AgentCommandSync;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class VersionDeleted extends AgentCommandSync{
    private Long versionId;
    private boolean succeeded;
    private String message;
    
    public VersionDeleted(){
        super(VersionDeleted.class.getSimpleName(), null, null, -1L);
    }
    
    @Builder
    public VersionDeleted(Long agentId, Long correlationId, long timeoutMilis, Long versionId, boolean succeeded){
        super(VersionDeleted.class.getSimpleName(), agentId, correlationId, timeoutMilis);
        this.versionId = versionId;
        this.succeeded = succeeded;
    }
}
