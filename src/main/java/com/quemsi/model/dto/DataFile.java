package com.quemsi.model.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.quemsi.commons.dto.HumanReadableSizeDeserializer;
import com.quemsi.commons.dto.HumanReadableSizeSerializer;
import com.quemsi.commons.persistence.BaseDto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataFile extends BaseDto<Long>{
    private Long version;
    private String storage;
    private String dir;
    private String name;
    private String contentType;
    @JsonDeserialize( using = HumanReadableSizeDeserializer.class)
	@JsonSerialize(using = HumanReadableSizeSerializer.class)
	private Long size;
}
