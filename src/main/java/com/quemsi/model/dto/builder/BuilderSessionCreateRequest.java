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
public class BuilderSessionCreateRequest implements Serializable {
    private BuilderMode mode;
    /** Defaults to DATASOURCE when null (Clear/Drop/UpdateSequences). */
    private BuilderSchemaSource schemaSource;
    private String datasourceName;
    /** Present when schemaSource is DATA_VERSION (MaskColumns / Browse / Upsert from StoredData). */
    private String dataName;
    private String storageName;
    private Long versionId;
    private Long fileId;
    /** Existing step fragment (e.g. all/tables) without datasource ownership checks on agent. */
    private Map<String, Object> draftConfig;
    private String returnUrl;
}
