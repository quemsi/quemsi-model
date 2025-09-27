package com.quemsi.model.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import com.quemsi.commons.persistence.BaseDto;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Builder
public class AgentError extends BaseDto<Long>{
    private Long agentId;
	private boolean seen;
	private String type;
	private String name;
	private String message;
    private String error;
	private LocalDate date;
	private LocalTime time;
	private String ago;
	private ObjectReference agent;
}
