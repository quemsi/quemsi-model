package com.biddflux.model.dto;

import com.biddflux.commons.persistence.BaseDto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AgentError extends BaseDto<Long>{
    private Long agentId;
	private boolean read;
	private String type;
	private String name;
	private String message;
    private String error;
}
