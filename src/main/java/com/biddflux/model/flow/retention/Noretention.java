package com.biddflux.model.flow.retention;

import com.biddflux.model.flow.out.Storage;

import lombok.Setter;

public class Noretention implements RetentionPolicy{

    @Setter
    private Storage storage;
    
    @Override
    public String getName() {
        return "noretention";
    }

    
    @Override
    public void clear() {
    }

}
