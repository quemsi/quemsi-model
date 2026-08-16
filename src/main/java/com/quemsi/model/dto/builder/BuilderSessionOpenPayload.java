package com.quemsi.model.dto.builder;

import java.io.Serializable;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuilderSessionOpenPayload implements Serializable {
    private String sessionId;
    private BuilderMode mode;
    private BuilderSchemaSource schemaSource;
    private String datasourceName;
    /** Archive coords when schemaSource is DATA_VERSION (same shape as download grant). */
    private String storageName;
    private String dir;
    private String fileName;
    private Long versionId;
    private String contentType;
    private Long size;
    private Map<String, Object> draftConfig;
    private String returnUrl;
}
