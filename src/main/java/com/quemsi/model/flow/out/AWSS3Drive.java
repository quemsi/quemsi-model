package com.quemsi.model.flow.out;

import lombok.Data;

@Data
public class AWSS3Drive {
    private String name;
    private String accessKey;
    private String secretKey;
    private String region;
    private String bucketName;
    private String storageRoot;
    private Long capacity;
    private Long usedSize;
    private boolean useEnvVar;
}
