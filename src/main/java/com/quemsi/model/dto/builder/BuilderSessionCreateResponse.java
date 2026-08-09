package com.quemsi.model.dto.builder;

import java.io.Serializable;
import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuilderSessionCreateResponse implements Serializable {
    private String sessionId;
    private String builderUrl;
    private Instant expiresAt;
}
