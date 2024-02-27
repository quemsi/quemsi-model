package com.biddflux.model.dto;

import java.util.List;

import com.biddflux.commons.persistence.BaseDto;
import com.biddflux.commons.persistence.Views;
import com.fasterxml.jackson.annotation.JsonView;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataVersion extends BaseDto<Long>{
    @JsonView(Views.OnlyIdName.class)
	private DataGroup data;
	private List<Tag> tags;
	private List<DataFile> files;
}
