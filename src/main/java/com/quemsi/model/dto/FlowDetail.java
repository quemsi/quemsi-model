package com.quemsi.model.dto;

import java.util.List;
import java.util.Map;

import com.quemsi.commons.persistence.BaseDto;
import com.quemsi.model.dto.agent.AgentReference;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class FlowDetail extends BaseDto<Long>{
	@NotEmpty
	private String name;
	@NotEmpty
	private String title;
	@NotNull
	private DataGroup data;
	private boolean back;
	private String timer;
	private List<Map<String, Object>> steps;
	@NotEmpty
	private String model;
	@NotNull
	private AgentReference agent;
	private int displayOrder;
}
