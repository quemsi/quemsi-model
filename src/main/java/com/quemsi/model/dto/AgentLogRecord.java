package com.quemsi.model.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentLogRecord{
    private Long agentId;
    private Long flowExecutionId;
    private Long flowExecutionStepId;
    private String message;
    private String level;
    private LocalDateTime timestamp;
}
