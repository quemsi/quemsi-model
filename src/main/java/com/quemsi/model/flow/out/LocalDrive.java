package com.quemsi.model.flow.out;

import com.quemsi.commons.persistence.Views;
import com.quemsi.commons.util.Exceptions;
import com.fasterxml.jackson.annotation.JsonView;

import lombok.Data;

@Data
public class LocalDrive {
    @JsonView(Views.OnlyIdName.class)
	private String name;
    @JsonView(Views.BasicInfo.class)
	private String storageRoot;
    @JsonView(Views.BasicInfo.class)
	private long capacity;
    @JsonView(Views.BasicInfo.class)
	private long usedSize;
    
    
    public void checkForCapacity(long size){
        if((getUsedSize() + size) > capacity){
            throw Exceptions.badRequest("not-enough-capacity").withExtra("localDrive", name)
                .withExtra("freeSpace", capacity - getUsedSize())
                .withExtra("fileSize", size).get();
        }
    }
}
