package com.quemsi.model.dto.agent;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class TestAWSS3Drive extends AgentCommandSync{
    private String accessKey;
    private String secretKey;
    private String region;
    private String bucketName;
    private boolean useEnvVar;

    public TestAWSS3Drive(){
        super(TestAWSS3Drive.class.getSimpleName(), null, null, -1L);
    }
    
    @Builder
    public TestAWSS3Drive(Long agentId, Long correlationId, long timeoutMilis, String accessKey, String secretKey, String region, String bucketName, boolean useEnvVar){
        super(TestAWSS3Drive.class.getSimpleName(), agentId, correlationId, timeoutMilis);
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
        this.bucketName = bucketName;
        this.useEnvVar = useEnvVar;
    }
}
