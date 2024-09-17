package com.biddflux.model.dto;

import java.io.StringWriter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import com.biddflux.commons.persistence.BaseDto;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

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
    private DataVersion version;
	private List<FlowExecutionStep> steps;
    private String logs;
	
    public StringWriter logWriter() {
        StringWriter sw = new StringWriter();
        if(logs != null) {
            sw.write(logs);
        }
        return sw;
    }
    
    @Data
    @EqualsAndHashCode(callSuper = true)
    @NoArgsConstructor
    public static class FlowExecutionStep extends BaseDto<Long>{
        private Long flowExecutionId;
        private String type;
        private Integer ord;
        private FlowExecutionStatus status;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
        private Duration duration;
        private String logs;
	
        public StringWriter logWriter() {
            StringWriter sw = new StringWriter();
            if(logs != null) {
                sw.write(logs);
            }
            return sw;
        }

        @Builder
        private FlowExecutionStep(Long id, Boolean active, Long flowExecutionId, String type, Integer ord, FlowExecutionStatus status, LocalDateTime startedAt, LocalDateTime finishedAt, Duration duration, String logs){
            super(id, active==null?true:active.booleanValue());
            this.flowExecutionId = flowExecutionId;
            this.type = type;
            this.ord = ord;
            this.status = status;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.duration = duration;
            this.logs = logs;
        }
    }
}
