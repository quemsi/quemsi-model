package com.quemsi.model.dto;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadGrantPayload implements Serializable {
    private String storageName;
    private String dir;
    private String fileName;
    private Long versionId;
    private String contentType;
    private Long size;
}
