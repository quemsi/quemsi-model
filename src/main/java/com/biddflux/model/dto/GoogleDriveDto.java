package com.biddflux.model.dto;

import lombok.Data;

@Data
public class GoogleDriveDto {
	private String name;
	private boolean connected;
	private String error;
	private String authUrl;
}
