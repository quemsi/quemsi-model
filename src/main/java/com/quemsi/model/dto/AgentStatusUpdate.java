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
public class AgentStatusUpdate implements Serializable {
    private Long agentId;
    private AgentStatus status;
}
