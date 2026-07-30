package com.quemsi.model.flow.redis;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.model.dto.ClearRedisConfig;
import com.quemsi.model.dto.RedisClearMode;
import com.quemsi.model.dto.RedisConnectionMode;
import com.quemsi.model.flow.redis.RedisClearService.ClearResult;
import com.quemsi.model.flow.redis.RedisConnectionSupport.RedisSession;
import com.quemsi.model.flow.redis.RedisConnectionSupport.ResolvedCredentials;

import redis.clients.jedis.Jedis;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
public class RedisClearServiceIntegrationTest {

	static boolean dockerAvailable() {
		try {
			return DockerClientFactory.instance().isDockerAvailable();
		} catch (Throwable t) {
			return false;
		}
	}

	@Container
	@SuppressWarnings("resource")
	static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	private RedisClearService clearService;
	private ClearRedisConfig baseConfig;

	@BeforeEach
	public void setUp() {
		clearService = new RedisClearService();
		baseConfig = ClearRedisConfig.builder()
				.mode(RedisConnectionMode.STANDALONE)
				.host(redis.getHost())
				.port(redis.getMappedPort(6379))
				.database(0)
				.connectTimeoutMs(3000)
				.readTimeoutMs(5000)
				.build();

		try (RedisSession session = RedisConnectionSupport.open(baseConfig,
				RedisConnectionSupport.resolveCredentials(baseConfig))) {
			session.jedis().flushDB();
		}
	}

	@Test
	public void flushDbClearsSelectedDatabaseOnly() {
		seedKeys(0, "a", "b");
		seedKeys(1, "keep-me");

		ClearRedisConfig config = copyBase()
				.clearMode(RedisClearMode.FLUSHDB)
				.database(0)
				.dryRun(false)
				.build();

		clearService.clear(config, null);

		assertThat(exists(0, "a"), equalTo(false));
		assertThat(exists(0, "b"), equalTo(false));
		assertThat(exists(1, "keep-me"), equalTo(true));
	}

	@Test
	public void patternsDeleteMatchingKeys() {
		seedKeys(0, "app:cache:1", "app:cache:2", "other:1");

		ClearRedisConfig config = copyBase()
				.clearMode(RedisClearMode.PATTERNS)
				.patterns(List.of("app:cache:*"))
				.dryRun(false)
				.build();

		ClearResult result = clearService.clear(config, null);
		assertThat(result.matched(), equalTo(2L));
		assertThat(result.deleted(), equalTo(2L));
		assertThat(exists(0, "app:cache:1"), equalTo(false));
		assertThat(exists(0, "app:cache:2"), equalTo(false));
		assertThat(exists(0, "other:1"), equalTo(true));
	}

	@Test
	public void starPatternClearsAllKeysInDatabase() {
		seedKeys(0, "one", "two", "three");

		ClearRedisConfig config = copyBase()
				.clearMode(RedisClearMode.PATTERNS)
				.patterns(List.of("*"))
				.dryRun(false)
				.build();

		ClearResult result = clearService.clear(config, null);
		assertThat(result.matched(), equalTo(3L));
		assertThat(exists(0, "one"), equalTo(false));
		assertThat(exists(0, "two"), equalTo(false));
		assertThat(exists(0, "three"), equalTo(false));
	}

	@Test
	public void dryRunDoesNotDeleteKeysAndReportsSample() {
		seedKeys(0, "app:1", "app:2");

		ClearRedisConfig config = copyBase()
				.clearMode(RedisClearMode.PATTERNS)
				.patterns(List.of("app:*"))
				.dryRun(true)
				.build();

		ClearResult result = clearService.clear(config, null);
		assertThat(result.matched(), equalTo(2L));
		assertThat(result.deleted(), equalTo(0L));
		assertThat(result.sampleKeys().size(), greaterThan(0));
		assertThat(result.sampleKeys(), hasItem("app:1"));
		assertThat(exists(0, "app:1"), equalTo(true));
		assertThat(exists(0, "app:2"), equalTo(true));
	}

	@Test
	public void emptyPatternIsRejected() {
		ClearRedisConfig config = copyBase()
				.clearMode(RedisClearMode.PATTERNS)
				.patterns(List.of(""))
				.build();

		BaseRuntimeException ex = assertThrows(BaseRuntimeException.class, () -> clearService.clear(config, null));
		assertThat(ex.getMessageId(), equalTo("redis-pattern-required"));
	}

	@Test
	public void connectionTestPingSucceeds() {
		ResolvedCredentials credentials = RedisConnectionSupport.resolveCredentials(baseConfig);
		try (RedisSession session = RedisConnectionSupport.open(baseConfig, credentials)) {
			assertThat(session.jedis().ping(), equalTo("PONG"));
			assertThat(session.discoveredMaster(), equalTo(redis.getHost() + ":" + redis.getMappedPort(6379)));
		}
	}

	private ClearRedisConfig.ClearRedisConfigBuilder copyBase() {
		return ClearRedisConfig.builder()
				.mode(baseConfig.getMode())
				.host(baseConfig.getHost())
				.port(baseConfig.getPort())
				.database(0)
				.connectTimeoutMs(baseConfig.getConnectTimeoutMs())
				.readTimeoutMs(baseConfig.getReadTimeoutMs());
	}

	private void seedKeys(int database, String... keys) {
		ClearRedisConfig config = copyBase().database(database).build();
		try (RedisSession session = RedisConnectionSupport.open(config,
				RedisConnectionSupport.resolveCredentials(config))) {
			Jedis jedis = session.jedis();
			for (String key : keys) {
				jedis.set(key, "v");
			}
		}
	}

	private boolean exists(int database, String key) {
		ClearRedisConfig config = copyBase().database(database).build();
		try (RedisSession session = RedisConnectionSupport.open(config,
				RedisConnectionSupport.resolveCredentials(config))) {
			return session.jedis().exists(key);
		}
	}
}
