package com.quemsi.model.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

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
    private Long companyId;
    private Long agentId;
    private Long flowExecutionId;
    private Long flowExecutionStepId;
    /** Short human-readable line (VictoriaLogs _msg). */
    private String message;
    /** Stable error code when from BaseRuntimeException (e.g. unable-to-build-dbmodel). */
    private String messageId;
    /** Root-cause message only (no stack). */
    private String cause;
    /** Full stack trace; kept out of message/_msg for UI readability. */
    private String stackTrace;
    private String level;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private LocalDateTime timestamp;
    private String formattedTimestamp;
}
