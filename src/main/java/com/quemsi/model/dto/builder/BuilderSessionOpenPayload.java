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
    private String datasourceName;
    private Map<String, Object> draftConfig;
    private String returnUrl;
}
