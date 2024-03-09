package com.biddflux.model.flow.out;

import com.biddflux.commons.util.Exceptions;
import com.biddflux.model.flow.Flow;

import lombok.Getter;
import lombok.Setter;

public abstract class AbstractStorage implements Storage{
	@Setter
	@Getter
	protected String name;
	@Setter
	@Getter
    protected String rootPath;
    
	@Override
	public boolean recordFiles() {
		return true;
	}
	
	@Override
	public void init(Flow f) {
	}
	
	protected String fileName(String absolutePath) {
		if(absolutePath.lastIndexOf(java.io.File.separator) > -1) {
			return absolutePath.substring(absolutePath.lastIndexOf(java.io.File.separator) + 1);
		}
		throw Exceptions.server("filename-not-found").withExtra("absolutePath", absolutePath).get();
	}
}
