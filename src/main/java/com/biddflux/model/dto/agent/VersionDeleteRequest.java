package com.biddflux.model.dto.agent;

import com.biddflux.model.dto.DataVersion;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class VersionDeleteRequest extends AgentCommand{
    private DataVersion version;

    @Builder
    public VersionDeleteRequest(Long agentId, DataVersion version){
        super(VersionDeleteRequest.class.getSimpleName(), agentId);
        this.version = version;
    }
}
