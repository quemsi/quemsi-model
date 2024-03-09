package com.biddflux.model.dto;

import java.util.List;

import com.biddflux.commons.persistence.BaseDto;
import com.biddflux.commons.persistence.Views;
import com.fasterxml.jackson.annotation.JsonView;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataGroup extends BaseDto<Long>{
    @NotEmpty
	@JsonView({Views.BasicInfo.class, Views.Agent.AgentModel.class})
	private String name;
	@NotEmpty
	@JsonView({Views.BasicInfo.class, Views.Agent.AgentModel.class})
	private String title;
	@NotNull
	@JsonView({Views.BasicInfo.class, Views.Agent.AgentModel.class})
	private DataType type;
	@NotEmpty
	@JsonView({Views.BasicInfo.class, Views.Agent.AgentModel.class})
	private String retentionPolicy;
	private List<DataVersion> versions;
	private Long companyId;
}
