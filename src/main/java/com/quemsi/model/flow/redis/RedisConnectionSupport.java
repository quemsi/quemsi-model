package com.quemsi.model.flow.redis;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.StringUtils;
import com.quemsi.model.dto.ClearRedisConfig;
import com.quemsi.model.dto.RedisClearMode;
import com.quemsi.model.dto.RedisConnectionMode;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisSentinelPool;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisException;

public final class RedisConnectionSupport {
	public static final int MAX_FAILOVER_RETRIES = 3;
	public static final int DRY_RUN_SAMPLE_SIZE = 20;

	private RedisConnectionSupport() {
	}

	public static ResolvedCredentials resolveCredentials(ClearRedisConfig config) {
		if (config == null) {
			throw Exceptions.badRequest("redis-config-required").get();
		}
		boolean useEnvVar = Boolean.TRUE.equals(config.getUseEnvVar());
		return new ResolvedCredentials(
				resolveOptional(config.getUsername(), useEnvVar, "username"),
				resolveOptional(config.getPassword(), useEnvVar, "password"),
				resolveOptional(config.getSentinelUsername(), useEnvVar, "sentinelUsername"),
				resolveOptional(config.getSentinelPassword(), useEnvVar, "sentinelPassword"));
	}

	public static void validateConnectionConfig(ClearRedisConfig config) {
		if (config == null) {
			throw Exceptions.badRequest("redis-config-required").get();
		}
		RedisConnectionMode mode = config.getMode() != null ? config.getMode() : RedisConnectionMode.STANDALONE;
		if (mode == RedisConnectionMode.STANDALONE) {
			if (StringUtils.isEmptyOrNull(config.getHost())) {
				throw Exceptions.badRequest("redis-host-needed").get();
			}
			if (config.getPort() == null || config.getPort() <= 0) {
				throw Exceptions.badRequest("redis-port-needed").get();
			}
		} else if (mode == RedisConnectionMode.SENTINEL) {
			if (config.getSentinels() == null || config.getSentinels().isEmpty()) {
				throw Exceptions.badRequest("redis-sentinels-needed").get();
			}
			if (StringUtils.isEmptyOrNull(config.getMasterName())) {
				throw Exceptions.badRequest("redis-master-name-needed").get();
			}
		} else {
			throw Exceptions.badRequest("redis-mode-needed").get();
		}
		Integer database = config.getDatabase() != null ? config.getDatabase() : 0;
		if (database < 0) {
			throw Exceptions.badRequest("redis-database-invalid").get();
		}
	}

	public static void validateClearConfig(ClearRedisConfig config) {
		validateConnectionConfig(config);
		RedisClearMode clearMode = config.getClearMode() != null ? config.getClearMode() : RedisClearMode.FLUSHDB;
		if (clearMode == RedisClearMode.PATTERNS) {
			List<String> patterns = config.getPatterns();
			if (patterns == null || patterns.isEmpty()) {
				throw Exceptions.badRequest("redis-pattern-required").get();
			}
			boolean hasNonBlank = false;
			for (String pattern : patterns) {
				if (pattern != null && !pattern.isBlank()) {
					hasNonBlank = true;
				} else if (pattern != null && pattern.isEmpty()) {
					throw Exceptions.badRequest("redis-pattern-required").get();
				}
			}
			if (!hasNonBlank) {
				throw Exceptions.badRequest("redis-pattern-required").get();
			}
		}
	}

	public static RedisSession open(ClearRedisConfig config, ResolvedCredentials credentials) {
		validateConnectionConfig(config);
		RedisConnectionMode mode = config.getMode() != null ? config.getMode() : RedisConnectionMode.STANDALONE;
		int connectTimeout = config.getConnectTimeoutMs() != null ? config.getConnectTimeoutMs() : 3000;
		int readTimeout = config.getReadTimeoutMs() != null ? config.getReadTimeoutMs() : 5000;
		boolean tls = Boolean.TRUE.equals(config.getTls());
		int database = config.getDatabase() != null ? config.getDatabase() : 0;

		try {
			if (mode == RedisConnectionMode.SENTINEL) {
				return openSentinel(config, credentials, connectTimeout, readTimeout, tls, database);
			}
			return openStandalone(config, credentials, connectTimeout, readTimeout, tls, database);
		} catch (com.quemsi.commons.util.BaseRuntimeException ex) {
			throw ex;
		} catch (JedisDataException ex) {
			throw mapDataException(ex);
		} catch (JedisConnectionException ex) {
			throw mapConnectionException(mode, config, ex);
		} catch (JedisException ex) {
			throw Exceptions.server("redis-connection-test-failed").withCause(ex).get();
		}
	}

	private static RedisSession openStandalone(ClearRedisConfig config, ResolvedCredentials credentials,
			int connectTimeout, int readTimeout, boolean tls, int database) {
		HostAndPort hostAndPort = new HostAndPort(config.getHost(), config.getPort());
		JedisClientConfig clientConfig = buildClientConfig(credentials.username(), credentials.password(),
				connectTimeout, readTimeout, tls, database);
		Jedis jedis = new Jedis(hostAndPort, clientConfig);
		try {
			jedis.ping();
		} catch (RuntimeException ex) {
			jedis.close();
			throw ex;
		}
		return new RedisSession(jedis, null, hostAndPort.toString());
	}

	private static RedisSession openSentinel(ClearRedisConfig config, ResolvedCredentials credentials,
			int connectTimeout, int readTimeout, boolean tls, int database) {
		Set<HostAndPort> sentinelHosts = new HashSet<>();
		for (String sentinel : config.getSentinels()) {
			if (!StringUtils.isEmptyOrNull(sentinel)) {
				sentinelHosts.add(HostAndPort.from(sentinel.trim()));
			}
		}
		if (sentinelHosts.isEmpty()) {
			throw Exceptions.badRequest("redis-sentinels-needed").get();
		}

		JedisClientConfig masterConfig = buildClientConfig(credentials.username(), credentials.password(),
				connectTimeout, readTimeout, tls, database);
		JedisClientConfig sentinelConfig = buildSentinelClientConfig(credentials.sentinelUsername(),
				credentials.sentinelPassword(), connectTimeout, readTimeout, tls);

		GenericObjectPoolConfig<Jedis> poolConfig = new GenericObjectPoolConfig<>();
		poolConfig.setMaxTotal(1);
		poolConfig.setMaxIdle(1);
		poolConfig.setMinIdle(0);

		JedisSentinelPool pool = null;
		try {
			pool = new JedisSentinelPool(config.getMasterName(), sentinelHosts, poolConfig, masterConfig,
					sentinelConfig);
			Jedis jedis = pool.getResource();
			HostAndPort master = pool.getCurrentHostMaster();
			String discoveredMaster = master != null ? master.toString() : "unknown";
			try {
				jedis.ping();
			} catch (RuntimeException ex) {
				jedis.close();
				pool.close();
				throw ex;
			}
			return new RedisSession(jedis, pool, discoveredMaster);
		} catch (JedisConnectionException ex) {
			if (pool != null) {
				try {
					pool.close();
				} catch (Exception ignored) {
					// ignore close failures while mapping original error
				}
			}
			throw mapConnectionException(RedisConnectionMode.SENTINEL, config, ex);
		} catch (RuntimeException ex) {
			if (pool != null) {
				try {
					pool.close();
				} catch (Exception ignored) {
					// ignore
				}
			}
			throw ex;
		}
	}

	private static JedisClientConfig buildClientConfig(String username, String password, int connectTimeout,
			int readTimeout, boolean tls, int database) {
		DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
				.connectionTimeoutMillis(connectTimeout)
				.socketTimeoutMillis(readTimeout)
				.database(database)
				.ssl(tls);
		if (!StringUtils.isEmptyOrNull(username) && !StringUtils.isEmptyOrNull(password)) {
			builder.user(username).password(password);
		} else if (!StringUtils.isEmptyOrNull(password)) {
			builder.password(password);
		}
		return builder.build();
	}

	private static JedisClientConfig buildSentinelClientConfig(String username, String password, int connectTimeout,
			int readTimeout, boolean tls) {
		DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
				.connectionTimeoutMillis(connectTimeout)
				.socketTimeoutMillis(readTimeout)
				.ssl(tls);
		if (!StringUtils.isEmptyOrNull(username) && !StringUtils.isEmptyOrNull(password)) {
			builder.user(username).password(password);
		} else if (!StringUtils.isEmptyOrNull(password)) {
			builder.password(password);
		}
		return builder.build();
	}

	public static RuntimeException mapDataException(JedisException ex) {
		String message = ex.getMessage() != null ? ex.getMessage() : "";
		if (message.contains("READONLY") || message.contains("read only replica")) {
			return Exceptions.server("redis-target-is-replica").withCause(ex).get();
		}
		if (message.toLowerCase().contains("auth") || message.toLowerCase().contains("wrong pass")
				|| message.toLowerCase().contains("noauth") || message.toLowerCase().contains("invalid password")
				|| message.toLowerCase().contains("wrongpass") || message.toLowerCase().contains("noperim")
				|| message.toLowerCase().contains("noperm")) {
			return Exceptions.server("redis-auth-failed").withCause(ex).get();
		}
		return Exceptions.server("redis-connection-test-failed").withCause(ex).get();
	}

	public static RuntimeException mapConnectionException(RedisConnectionMode mode, ClearRedisConfig config,
			JedisConnectionException ex) {
		String message = ex.getMessage() != null ? ex.getMessage() : "";
		if (mode == RedisConnectionMode.SENTINEL) {
			if (message.toLowerCase().contains("sentinel") || message.toLowerCase().contains("can not get master")
					|| message.toLowerCase().contains("cannot get master")) {
				return Exceptions.server("redis-sentinels-unreachable").withCause(ex)
						.withExtra("masterName", config.getMasterName()).get();
			}
			return Exceptions.server("redis-discovered-master-unreachable").withCause(ex)
					.withExtra("masterName", config.getMasterName()).get();
		}
		return Exceptions.server("redis-connection-test-failed").withCause(ex)
				.withExtra("host", config.getHost()).withExtra("port", config.getPort()).get();
	}

	public static boolean isFailoverCandidate(Throwable ex) {
		Throwable current = ex;
		while (current != null) {
			if (current instanceof JedisConnectionException) {
				return true;
			}
			String message = current.getMessage() != null ? current.getMessage() : "";
			if (message.contains("READONLY") || message.contains("read only replica")) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private static String resolveOptional(String value, boolean useEnvVar, String fieldName) {
		if (StringUtils.isEmptyOrNull(value)) {
			return null;
		}
		if (!useEnvVar) {
			return value;
		}
		String resolved = System.getenv(value);
		if (StringUtils.isEmptyOrNull(resolved)) {
			throw Exceptions.badRequest("environment-vars-not-set").withExtra("variable", value)
					.withExtra("field", fieldName).get();
		}
		return resolved;
	}

	public record ResolvedCredentials(String username, String password, String sentinelUsername,
			String sentinelPassword) {
	}

	public static final class RedisSession implements AutoCloseable {
		private final Jedis jedis;
		private final JedisSentinelPool pool;
		private final String discoveredMaster;

		public RedisSession(Jedis jedis, JedisSentinelPool pool, String discoveredMaster) {
			this.jedis = jedis;
			this.pool = pool;
			this.discoveredMaster = discoveredMaster;
		}

		public Jedis jedis() {
			return jedis;
		}

		public String discoveredMaster() {
			return discoveredMaster;
		}

		@Override
		public void close() {
			try {
				if (jedis != null) {
					jedis.close();
				}
			} finally {
				if (pool != null) {
					pool.close();
				}
			}
		}
	}

	@FunctionalInterface
	public interface ProgressLogger {
		void info(String message, Object... args);
	}

	public static void withProgress(ProgressLogger logger, String message, Object... args) {
		if (logger != null) {
			logger.info(message, args);
		}
	}
}
