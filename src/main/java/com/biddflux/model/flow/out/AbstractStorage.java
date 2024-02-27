package com.biddflux.model.flow.out;

import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.util.Date;
import java.util.TimeZone;

import org.springframework.beans.factory.annotation.Autowired;

import com.biddflux.EnvironmentVars;
import com.biddflux.commons.util.Exceptions;
import com.biddflux.model.flow.Flow;
import com.biddflux.model.flow.file.ChecksumProducer;

import lombok.Getter;
import lombok.Setter;

public abstract class AbstractStorage implements Storage{
	protected static final String VERSION_DIR = "old-versions";
	@Setter
	@Getter
	protected String name;
	@Setter
	@Getter
    protected String rootPath;
    @Setter
	protected boolean applyVersion = true;
	@Setter
	protected SimpleDateFormat versionDateFormat; 
	@Setter
	@Autowired
	protected ChecksumProducer checkSumProducer;
	@Override
	public boolean recordFiles() {
		return true;
	}
	
	@Override
	public void init(Flow f, EnvironmentVars env) {
		versionDateFormat = new SimpleDateFormat(env.getVersionFormat());
		versionDateFormat.setTimeZone(TimeZone.getTimeZone(ZoneId.of(env.getZoneIdOf())));
	}
	
	protected String fileName(String absolutePath) {
		if(absolutePath.lastIndexOf(java.io.File.separator) > -1) {
			return absolutePath.substring(absolutePath.lastIndexOf(java.io.File.separator) + 1);
		}
		throw Exceptions.server("filename-not-found").withExtra("absolutePath", absolutePath).get();
	}
	
	protected String versionedName(String fileName) {
		return  fileName.substring(0, fileName.lastIndexOf('.')) + versionDateFormat.format(new Date(System.currentTimeMillis())) 
			+ fileName.substring(fileName.lastIndexOf('.'));
	}
	
}
