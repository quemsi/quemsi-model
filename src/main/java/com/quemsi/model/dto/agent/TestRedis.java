package com.quemsi.model.dto.agent;

import java.util.ArrayList;
import java.util.List;

import com.quemsi.model.dto.ClearRedisConfig;
import com.quemsi.model.dto.RedisConnectionMode;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class TestRedis extends AgentCommandSync {
	private RedisConnectionMode mode;
	private String host;
	private Integer port;
	private List<String> sentinels = new ArrayList<>();
	private String masterName;
	private String sentinelUsername;
	private String sentinelPassword;
	private String username;
	private String password;
	private boolean useEnvVar;
	private boolean tls;
	private Integer connectTimeoutMs;
	private Integer readTimeoutMs;
	private Integer database;

	public TestRedis() {
		super(TestRedis.class.getSimpleName(), null, null, -1L);
	}

	@Builder
	public TestRedis(Long agentId, Long correlationId, long timeoutMilis, RedisConnectionMode mode, String host,
			Integer port, List<String> sentinels, String masterName, String sentinelUsername, String sentinelPassword,
			String username, String password, boolean useEnvVar, boolean tls, Integer connectTimeoutMs,
			Integer readTimeoutMs, Integer database) {
		super(TestRedis.class.getSimpleName(), agentId, correlationId, timeoutMilis);
		this.mode = mode;
		this.host = host;
		this.port = port;
		this.sentinels = sentinels != null ? sentinels : new ArrayList<>();
		this.masterName = masterName;
		this.sentinelUsername = sentinelUsername;
		this.sentinelPassword = sentinelPassword;
		this.username = username;
		this.password = password;
		this.useEnvVar = useEnvVar;
		this.tls = tls;
		this.connectTimeoutMs = connectTimeoutMs;
		this.readTimeoutMs = readTimeoutMs;
		this.database = database;
	}

	public ClearRedisConfig toConfig() {
		return ClearRedisConfig.builder()
				.mode(mode != null ? mode : RedisConnectionMode.STANDALONE)
				.host(host)
				.port(port)
				.sentinels(sentinels)
				.masterName(masterName)
				.sentinelUsername(sentinelUsername)
				.sentinelPassword(sentinelPassword)
				.username(username)
				.password(password)
				.useEnvVar(useEnvVar)
				.tls(tls)
				.connectTimeoutMs(connectTimeoutMs)
				.readTimeoutMs(readTimeoutMs)
				.database(database != null ? database : 0)
				.build();
	}

	public static TestRedis fromConfig(Long agentId, long timeoutMilis, ClearRedisConfig config) {
		return TestRedis.builder()
				.agentId(agentId)
				.timeoutMilis(timeoutMilis)
				.mode(config.getMode())
				.host(config.getHost())
				.port(config.getPort())
				.sentinels(config.getSentinels())
				.masterName(config.getMasterName())
				.sentinelUsername(config.getSentinelUsername())
				.sentinelPassword(config.getSentinelPassword())
				.username(config.getUsername())
				.password(config.getPassword())
				.useEnvVar(Boolean.TRUE.equals(config.getUseEnvVar()))
				.tls(Boolean.TRUE.equals(config.getTls()))
				.connectTimeoutMs(config.getConnectTimeoutMs())
				.readTimeoutMs(config.getReadTimeoutMs())
				.database(config.getDatabase())
				.build();
	}
}
