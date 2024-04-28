package com.biddflux.model.dto;

import com.biddflux.commons.persistence.Views;
import com.fasterxml.jackson.annotation.JsonView;

import lombok.Data;

@Data
public class ObjectReference {
    @JsonView(Views.OnlyIdName.class)
    private Long id;
}
