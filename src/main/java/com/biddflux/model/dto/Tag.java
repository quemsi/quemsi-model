package com.biddflux.model.dto;

import com.biddflux.commons.persistence.BaseDto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class Tag extends BaseDto<Long>{
    private String name;
    private String val;
}
