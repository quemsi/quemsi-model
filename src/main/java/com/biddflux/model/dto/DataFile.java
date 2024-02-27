package com.biddflux.model.dto;

import com.biddflux.commons.persistence.BaseDto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataFile extends BaseDto<Long>{
    private DataVersion version;
    private String storage;
    private String dir;
    private String name;
    private String contentType;
    private Long size;
}
