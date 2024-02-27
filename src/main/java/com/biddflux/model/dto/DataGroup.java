package com.biddflux.model.dto;

import java.util.List;

import com.biddflux.commons.persistence.BaseDto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataGroup extends BaseDto<Long>{
    private String name;
	private String title;
	private DataType type;
	private String retentionPolicy;
	private List<DataVersion> versions;
}
