package com.quemsi.model.dto;

import com.quemsi.commons.persistence.BaseDto;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class Tag extends BaseDto<Long>{
    @Builder
    public Tag(Long id, boolean active, String name, String val){
        super(id, active);
        this.name = name;
        this.val = val;
    }
    private String name;
    private String val;
}
