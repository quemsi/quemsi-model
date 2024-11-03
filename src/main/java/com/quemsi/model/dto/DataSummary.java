package com.quemsi.model.dto;

import com.quemsi.commons.persistence.BaseDto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataSummary extends BaseDto<Long>{
    private String name;
	private String title;
	private DataType type;
	private String retentionPolicy;
	private DataVersion latestVersion;
	private Long companyId;
}
