package com.quemsi.model.dto.agent;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class TestFolderAccess extends AgentCommandSync{
    private String path;

    public TestFolderAccess(){
        super(TestFolderAccess.class.getSimpleName(), null, null, -1L);
    }
    
    @Builder
    public TestFolderAccess(Long agentId, Long correlationId, long timeoutMilis, String path){
        super(TestFolderAccess.class.getSimpleName(), agentId, correlationId, timeoutMilis);
        this.path = path;
    }
}
