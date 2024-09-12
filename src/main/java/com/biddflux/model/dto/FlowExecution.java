package com.biddflux.model.dto;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import com.biddflux.commons.persistence.BaseDto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class FlowExecution extends BaseDto<Long>{
	@NotNull
    private Long flowId;
    private String flowName;
    @NotNull
    private FlowExecutionStatus status;
	private LocalDateTime startedAt;
	private LocalDateTime finishedAt;
	private Integer completedSteps;
    private Integer numberOfSteps;
    private Duration duration;
    private List<FlowExecutionStep> steps;
	
    @Data
    @EqualsAndHashCode(callSuper = true)
	public static class FlowExecutionStep extends BaseDto<Long>{
        private Long flowExecutionId;
        private String type;
        private Integer ord;
        private FlowExecutionStatus status;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
        private Duration duration;
    }
}
