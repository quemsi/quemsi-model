package com.quemsi.model.dto.agent.onapi;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.model.dto.agent.AgentCommand;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class NotifyError extends AgentCommand{
    private String entityType;
    private String entityName;
    private String messageId;
    private Map<String, Object> extra;
    private String stackTrace;

    @Builder
    public NotifyError(Long agentId, String entityType, String entityName, BaseRuntimeException exception){
        super(NotifyError.class.getSimpleName(), agentId);
        this.entityType = entityType;
        this.entityName = entityName;
        if(exception != null){
            exception(exception);
        }
    }

    public NotifyError exception(BaseRuntimeException exception){
        this.messageId = exception.getMessageId();
        this.extra = exception.getExtra();
        this.entityType = exception.getEntityType();
        this.entityName = exception.getEntityName();
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exception.printStackTrace(pw);
        stackTrace = sw.toString();
        return this;
    }
}
