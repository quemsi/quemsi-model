package com.quemsi.model.dto.agent.onapi;

import com.quemsi.model.dto.agent.AgentCommand;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class VersionDeleted extends AgentCommand{
    private Long versionId;
    
    @Builder
    public VersionDeleted(Long agentId, Long versionId){
        super(VersionDeleted.class.getSimpleName(), agentId);
        this.versionId = versionId;
    }
}
