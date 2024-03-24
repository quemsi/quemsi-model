package com.biddflux.model.dto.agent.onapi;

import com.biddflux.commons.util.BaseRuntimeException;
import com.biddflux.model.dto.agent.AgentCommand;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class NotifyError extends AgentCommand{
    private String entityType;
    private String entityName;
    private BaseRuntimeException exception;

    @Builder
    public NotifyError(Long agentId, String entityType, String entityName, BaseRuntimeException exception){
        super(NotifyError.class.getSimpleName(), agentId);
        this.entityType = entityType;
        this.entityName = entityName;
        this.exception = exception;
    }
}
