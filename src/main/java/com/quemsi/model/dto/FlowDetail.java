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
	/** Optional key/value tags to pre-fill (forward flows) or merge on timer runs. Stored inside serialized model JSON. */
	private Map<String, String> defaultExecutionTags;
	private List<Map<String, Object>> steps;
	@NotEmpty
	private String model;
	@NotNull
	private AgentReference agent;
	private int displayOrder;
	/** True when the agent failed to initialize this flow. Execute is blocked until init succeeds. */
	private boolean inerror;
}
