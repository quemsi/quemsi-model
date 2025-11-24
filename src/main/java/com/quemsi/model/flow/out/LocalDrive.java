package com.quemsi.model.flow.out;

import com.quemsi.commons.util.Exceptions;

import lombok.Data;

@Data
public class LocalDrive {
	private String name;
	private String storageRoot;
	private long capacity;
	private long usedSize;
    
    
    public void checkForCapacity(long size){
        if((getUsedSize() + size) > capacity){
            throw Exceptions.badRequest("not-enough-capacity").withExtra("localDrive", name)
                .withExtra("freeSpace", capacity - getUsedSize())
                .withExtra("fileSize", size).get();
        }
    }
}
