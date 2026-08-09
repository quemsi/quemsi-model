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
public class BuilderSessionResult implements Serializable {
    private String sessionId;
    private BuilderMode mode;
    private BuilderSessionStatus status;
    private Map<String, Object> resultConfig;
}
