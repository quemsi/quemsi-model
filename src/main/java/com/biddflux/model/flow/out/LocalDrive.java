package com.biddflux.model.flow.out;

import com.biddflux.commons.persistence.Views;
import com.biddflux.commons.util.Exceptions;
import com.fasterxml.jackson.annotation.JsonView;

import lombok.Data;

@Data
public class LocalDrive {
    //TODO: how agent should know about the capacity
    // @JsonIgnore
    // @Autowired
    // private LocalDriveServiceImpl serviceImpl;
    @JsonView(Views.OnlyIdName.class)
	private String name;
    @JsonView(Views.BasicInfo.class)
	private String storageRoot;
    @JsonView(Views.BasicInfo.class)
	private long capacity;
    
    public void checkForCapacity(long size){
        if((getUsedSize() + size) > capacity){
            throw Exceptions.badRequest("not-enough-capacity").withExtra("localDrive", name)
                .withExtra("freeSpace", capacity - getUsedSize())
                .withExtra("fileSize", size).get();
        }
    }

    @JsonView(Views.BasicInfo.class)
	public long getUsedSize(){
        return 0L; //serviceImpl.findUsedSizeForDrive(name);
    }
}
