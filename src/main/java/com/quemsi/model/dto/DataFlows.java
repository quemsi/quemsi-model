package com.quemsi.model.dto;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

import com.quemsi.commons.persistence.BaseDto;
import com.quemsi.model.dto.agent.AgentReference;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataFlows extends BaseDto<Long>{
    @NotEmpty
	private String name;
	@NotEmpty
	private String title;
	@NotNull
	private DataType type;
	@NotEmpty
	private String retentionPolicy;
	private List<FlowSummary> forwardFlows;
	private List<FlowSummary> backwardFlows;
    private Long companyId;

    @Data
    public static class FlowSummary implements Serializable {
        private Long id;
        private String name;
        private String title;
        private String dataName;
        private boolean back;
        private NamedEntityReference timer;
        private Long lastExecutionId;
        private LocalDateTime lastExecutionTime;
        private FlowExecutionStatus status;
        private AgentReference agent;
    }
}
