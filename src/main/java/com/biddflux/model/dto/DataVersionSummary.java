package com.biddflux.model.dto;

import com.biddflux.commons.persistence.BaseDto;
import com.biddflux.commons.persistence.Views;
import com.fasterxml.jackson.annotation.JsonView;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class DataVersionSummary extends BaseDto<Long>{
	@Builder
	public DataVersionSummary(Long id, Long dataId){
		super(id, true);
		this.dataId = dataId;;
	}
    @JsonView(Views.OnlyIdName.class)
	private Long dataId;
}
