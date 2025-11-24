package com.quemsi.model.dto.agent;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class TestAzureBlobDrive extends AgentCommandSync{
    private String accountName;
    private String accountKey;
    private boolean useEnvVar;

    public TestAzureBlobDrive(){
        super(TestAzureBlobDrive.class.getSimpleName(), null, null, -1L);
    }
    
    @Builder
    public TestAzureBlobDrive(Long agentId, Long correlationId, long timeoutMilis, String accountName, String accountKey, boolean useEnvVar){
        super(TestAzureBlobDrive.class.getSimpleName(), agentId, correlationId, timeoutMilis);
        this.accountName = accountName;
        this.accountKey = accountKey;
        this.useEnvVar = useEnvVar;
    }
}
