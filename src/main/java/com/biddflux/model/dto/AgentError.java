package com.biddflux.model.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import com.biddflux.commons.persistence.BaseDto;
import com.biddflux.model.dto.agent.AgentReference;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
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
	private AgentReference agent;
}
