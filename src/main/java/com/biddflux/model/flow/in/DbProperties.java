package com.biddflux.model.flow.in;

import lombok.Data;

@Data
public class DbProperties {
	private Boolean preserveZip;
	private Boolean preserveSql;
	private String tempDir;
	private String dateFormat;
}
