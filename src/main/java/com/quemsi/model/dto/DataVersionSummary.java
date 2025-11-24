package com.quemsi.model.dto;

import com.quemsi.commons.persistence.BaseDto;

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
	private Long dataId;
}
