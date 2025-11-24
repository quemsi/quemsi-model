package com.quemsi.model.flow.out;

import lombok.Data;

@Data
public class AzureBlobDrive {
    private String name;
    private String accountName;
    private String accountKey;
    private String storageRoot;
    private Long capacity;
    private Long usedSize;
}
