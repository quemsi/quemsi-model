package com.quemsi.model.flow.redis;

import java.util.ArrayList;
import java.util.List;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.StringUtils;
import com.quemsi.model.dto.ClearRedisConfig;
import com.quemsi.model.dto.RedisClearMode;
import com.quemsi.model.dto.RedisConnectionMode;
import com.quemsi.model.flow.redis.RedisConnectionSupport.ProgressLogger;
import com.quemsi.model.flow.redis.RedisConnectionSupport.RedisSession;
import com.quemsi.model.flow.redis.RedisConnectionSupport.ResolvedCredentials;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

public class RedisClearService {
	public ClearResult clear(ClearRedisConfig config, ProgressLogger logger) {
		RedisConnectionSupport.validateClearConfig(config);
		ResolvedCredentials credentials = RedisConnectionSupport.resolveCredentials(config);
		RedisConnectionMode mode = config.getMode() != null ? config.getMode() : RedisConnectionMode.STANDALONE;
		int maxAttempts = mode == RedisConnectionMode.SENTINEL ? RedisConnectionSupport.MAX_FAILOVER_RETRIES : 1;

		RuntimeException lastFailure = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				if (attempt > 1) {
					RedisConnectionSupport.withProgress(logger,
							"Failover detected, restarting Redis clear (attempt {}/{})", attempt, maxAttempts);
				}
				return clearOnce(config, credentials, logger);
			} catch (RuntimeException ex) {
				lastFailure = ex;
				boolean canRetry = mode == RedisConnectionMode.SENTINEL
						&& attempt < maxAttempts
						&& RedisConnectionSupport.isFailoverCandidate(ex);
				if (!canRetry) {
					throw ex;
				}
				RedisConnectionSupport.withProgress(logger,
						"Redis clear interrupted by failover-like error: {}", ex.getMessage());
			}
		}
		throw Exceptions.server("redis-failover-during-clear").withCause(lastFailure).get();
	}

	private ClearResult clearOnce(ClearRedisConfig config, ResolvedCredentials credentials, ProgressLogger logger) {
		try (RedisSession session = RedisConnectionSupport.open(config, credentials)) {
			RedisConnectionSupport.withProgress(logger, "Connected to Redis at {}", session.discoveredMaster());
			RedisClearMode clearMode = config.getClearMode() != null ? config.getClearMode() : RedisClearMode.FLUSHDB;
			boolean dryRun = Boolean.TRUE.equals(config.getDryRun());
			int database = config.getDatabase() != null ? config.getDatabase() : 0;

			if (clearMode == RedisClearMode.FLUSHDB) {
				return flushDb(session.jedis(), database, dryRun, logger);
			}
			return clearPatterns(session.jedis(), config, dryRun, logger);
		}
	}

	private ClearResult flushDb(Jedis jedis, int database, boolean dryRun, ProgressLogger logger) {
		if (dryRun) {
			RedisConnectionSupport.withProgress(logger,
					"DRY RUN: Would execute FLUSHDB on database {}", database);
			return ClearResult.ok(0, 0, List.of());
		}
		try {
			String result = jedis.flushDB(redis.clients.jedis.args.FlushMode.ASYNC);
			RedisConnectionSupport.withProgress(logger, "FLUSHDB ASYNC completed on database {} (result={})", database,
					result);
			return ClearResult.ok(0, 0, List.of());
		} catch (com.quemsi.commons.util.BaseRuntimeException ex) {
			throw ex;
		} catch (redis.clients.jedis.exceptions.JedisException ex) {
			throw RedisConnectionSupport.mapDataException(ex);
		} catch (RuntimeException ex) {
			throw Exceptions.server("failed-to-clear-redis").withCause(ex).get();
		}
	}

	private ClearResult clearPatterns(Jedis jedis, ClearRedisConfig config, boolean dryRun, ProgressLogger logger) {
		int scanCount = config.getScanCount() != null && config.getScanCount() > 0 ? config.getScanCount() : 500;
		long matchedTotal = 0;
		long deletedTotal = 0;
		List<String> failures = new ArrayList<>();
		List<String> sample = new ArrayList<>();

		for (String pattern : config.getPatterns()) {
			if (StringUtils.isEmptyOrNull(pattern) || pattern.isBlank()) {
				throw Exceptions.badRequest("redis-pattern-required").get();
			}
			PatternResult patternResult = clearOnePattern(jedis, pattern.trim(), scanCount, dryRun, logger, sample);
			matchedTotal += patternResult.matched();
			deletedTotal += patternResult.deleted();
			failures.addAll(patternResult.failures());
		}

		if (dryRun) {
			RedisConnectionSupport.withProgress(logger,
					"DRY RUN summary: matched {} keys across {} pattern(s). Sample keys: {}",
					matchedTotal, config.getPatterns().size(), sample);
		} else {
			RedisConnectionSupport.withProgress(logger,
					"Pattern clear summary: matched {}, deleted {}, failures {}",
					matchedTotal, deletedTotal, failures.size());
		}

		if (!failures.isEmpty()) {
			throw Exceptions.server("failed-to-clear-redis")
					.withExtra("matched", matchedTotal)
					.withExtra("deleted", deletedTotal)
					.withExtra("failureCount", failures.size())
					.withExtra("failures", failures)
					.get();
		}
		return ClearResult.ok(matchedTotal, deletedTotal, sample);
	}

	private PatternResult clearOnePattern(Jedis jedis, String pattern, int scanCount, boolean dryRun,
			ProgressLogger logger, List<String> globalSample) {
		ScanParams params = new ScanParams().match(pattern).count(scanCount);
		String cursor = ScanParams.SCAN_POINTER_START;
		long matched = 0;
		long deleted = 0;
		List<String> failures = new ArrayList<>();

		RedisConnectionSupport.withProgress(logger, "{}Scanning keys for pattern '{}'", dryRun ? "DRY RUN: " : "",
				pattern);

		do {
			ScanResult<String> scanResult = jedis.scan(cursor, params);
			cursor = scanResult.getCursor();
			List<String> keys = scanResult.getResult();
			if (keys == null || keys.isEmpty()) {
				continue;
			}
			matched += keys.size();
			for (String key : keys) {
				if (globalSample.size() < RedisConnectionSupport.DRY_RUN_SAMPLE_SIZE) {
					globalSample.add(key);
				}
			}
			if (dryRun) {
				continue;
			}
			try {
				long unlinked = jedis.unlink(keys.toArray(new String[0]));
				deleted += unlinked;
			} catch (RuntimeException ex) {
				if (RedisConnectionSupport.isFailoverCandidate(ex)) {
					throw ex;
				}
				String failure = "Failed to UNLINK batch for pattern '" + pattern + "': " + ex.getMessage();
				failures.add(failure);
				RedisConnectionSupport.withProgress(logger, failure);
			}
		} while (!ScanParams.SCAN_POINTER_START.equals(cursor));

		RedisConnectionSupport.withProgress(logger,
				"{}Pattern '{}': matched={}, deleted={}, failures={}",
				dryRun ? "DRY RUN: " : "", pattern, matched, deleted, failures.size());
		return new PatternResult(matched, deleted, failures);
	}

	public record ClearResult(long matched, long deleted, List<String> sampleKeys) {
		public static ClearResult ok(long matched, long deleted, List<String> sampleKeys) {
			return new ClearResult(matched, deleted, sampleKeys != null ? sampleKeys : List.of());
		}
	}

	private record PatternResult(long matched, long deleted, List<String> failures) {
	}
}
