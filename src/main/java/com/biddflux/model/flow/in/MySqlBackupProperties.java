package com.biddflux.model.flow.in;

import lombok.Data;

@Data
public class MySqlBackupProperties {
	private Boolean preserveZip;
	private Boolean preserveSql;
	private String tempDir;
}
