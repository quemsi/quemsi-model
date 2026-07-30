package com.quemsi.model.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClearRedisConfig implements Serializable {
	private static final long serialVersionUID = 1L;

	@Builder.Default
	private RedisConnectionMode mode = RedisConnectionMode.STANDALONE;

	private String host;

	@Builder.Default
	private Integer port = 6379;

	@Builder.Default
	private List<String> sentinels = new ArrayList<>();

	private String masterName;
	private String sentinelUsername;
	private String sentinelPassword;
	private String username;
	private String password;

	@Builder.Default
	private Boolean useEnvVar = false;

	@Builder.Default
	private Boolean tls = false;

	@Builder.Default
	private Integer connectTimeoutMs = 3000;

	@Builder.Default
	private Integer readTimeoutMs = 5000;

	@Builder.Default
	private RedisClearMode clearMode = RedisClearMode.FLUSHDB;

	@Builder.Default
	private Integer database = 0;

	@Builder.Default
	private List<String> patterns = new ArrayList<>();

	@Builder.Default
	private Integer scanCount = 500;

	@Builder.Default
	private Boolean dryRun = false;
}
