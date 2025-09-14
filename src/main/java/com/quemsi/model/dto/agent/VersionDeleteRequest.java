package com.quemsi.model.dto.agent;

import com.quemsi.model.dto.DataVersion;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class VersionDeleteRequest extends AgentCommandSync{
    private DataVersion version;

    public VersionDeleteRequest(){
        super(VersionDeleteRequest.class.getSimpleName(), null, null, -1L);
    }

    @Builder
    public VersionDeleteRequest(Long agentId, Long correlationId, long timeoutMilis, DataVersion version){
        super(VersionDeleteRequest.class.getSimpleName(), agentId, correlationId, timeoutMilis);
        this.version = version;
    }
}
