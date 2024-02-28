package com.biddflux.model.dto;

import java.util.List;
import java.util.Map;

import com.biddflux.commons.persistence.BaseDto;
import com.biddflux.commons.persistence.Views;
import com.fasterxml.jackson.annotation.JsonView;

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
	@JsonView(Views.BasicInfo.class)
	private boolean active;
	@JsonView(Views.OnlyIdName.class)
	@NotEmpty
	private String name;
	@JsonView(Views.BasicInfo.class)
	@NotEmpty
	private String title;
	@JsonView(Views.BasicInfo.class)
	@NotNull
	private DataGroup data;
	@JsonView(Views.BasicInfo.class)
	private boolean back;
	@JsonView(Views.BasicInfo.class)
	private String timer;
	@JsonView(FlowViews.WithSteps.class)
	private List<Map<String, Object>> steps;
	@JsonView(Views.BasicInfo.class)
	@NotEmpty
	private String model;
	@NotNull
	private ObjectReference agent;
	@JsonView(Views.BasicInfo.class)
	private int displayOrder;

	public static class FlowViews{
		public class WithFK implements Views.OnlyIdName{	
		}
		public class WithSteps implements Views.BasicInfo{
		}
	}
}
