package com.quemsi.model.dto.agent.onapi;

import com.quemsi.model.dto.agent.AgentCommandSync;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class TestFolderAccessResult extends AgentCommandSync{
    private boolean success;
    private String message;
    private int errorCode;
    private String errorMessage;

    @Builder
    public TestFolderAccessResult(Long agentId, Long correlationId, long timeoutMilis, boolean success, int errorCode, String message, String errorMessage){
        super(TestFolderAccessResult.class.getSimpleName(), agentId, correlationId, timeoutMilis);
        this.success = success;
        this.errorCode = errorCode;
        this.message = message;
        this.errorMessage = errorMessage;
    }
}
