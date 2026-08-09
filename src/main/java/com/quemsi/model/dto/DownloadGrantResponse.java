package com.quemsi.model.dto;

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
public class DownloadGrantResponse implements Serializable {
    private String controlBaseUrl;
    private String ticket;
    private String downloadUrl;
    private Instant expiresAt;
}
