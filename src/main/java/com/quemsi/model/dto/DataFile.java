package com.quemsi.model.dto;

import java.util.HashSet;
import java.util.Set;

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
    private Set<String> storages = new HashSet<>();
    private String dir;
    private String name;
    private String contentType;
    @JsonDeserialize( using = HumanReadableSizeDeserializer.class)
	@JsonSerialize(using = HumanReadableSizeSerializer.class)
	private Long size;
    public void addStorage(String s){
        storages.add(s);
    }
}
