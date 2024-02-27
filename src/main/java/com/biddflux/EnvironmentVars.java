package com.biddflux;

import java.io.File;

import org.springframework.beans.factory.annotation.Value;

import lombok.Data;

@Data
public class EnvironmentVars {
	@Value("${BAKERUP_HOME}")
    private String homeDir;
	@Value("${google-drives-files:googleDrives}")
	private String googleDriveFilesRoot;
	@Value("${application.version-format}")
    private String versionFormat;
	@Value("${application.zoneid-of}")
    private String zoneIdOf;
	
	public String googleDriveFilesLocation() {
		return homeDir + File.separator + googleDriveFilesRoot;
	}
}
