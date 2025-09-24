package com.quemsi.model.dto;

import java.util.List;

import com.quemsi.commons.persistence.BaseDto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataGroup extends BaseDto<Long>{
    private boolean active;
	@NotEmpty
	private String name;
	@NotEmpty
	private String title;
	@NotNull
	private DataType type;
	@NotEmpty
	private String retentionPolicy;
	private List<DataVersion> versions;
	private Long companyId;
}
