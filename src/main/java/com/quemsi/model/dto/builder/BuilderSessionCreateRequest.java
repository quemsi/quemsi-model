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
    private String datasourceName;
    /** Existing step fragment (e.g. all/tables) without datasource ownership checks on agent. */
    private Map<String, Object> draftConfig;
    private String returnUrl;
}
