package com.quemsi.model.flow.redis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.LogMessage;
import com.quemsi.commons.util.StringUtils;
import com.quemsi.model.dto.ClearRedisConfig;
import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.redis.RedisClearService.ClearResult;

import lombok.Setter;

public class ClearRedis extends AbstractStep {
	@Setter
	private ClearRedisConfig config;
	@Setter
	private ObjectMapper objectMapper;
	@Setter
	private RedisClearService clearService = new RedisClearService();

	@Override
	public void execute(FlowContext context) {
		if (config == null) {
			throw Exceptions.badRequest("redis-config-required").get();
		}
		try {
			context.logStepInfo(context.getCurrentStep(),
					LogMessage.info("Starting ClearRedis (mode={}, clearMode={}, database={}, dryRun={})",
							config.getMode(), config.getClearMode(), config.getDatabase(), config.getDryRun()));

			ClearResult result = clearService.clear(config, (message, args) ->
					context.logStepInfo(context.getCurrentStep(), LogMessage.info(message, args)));

			context.logStepInfo(context.getCurrentStep(),
					LogMessage.info("ClearRedis completed (matched={}, deleted={})",
							result.matched(), result.deleted()));
		} catch (com.quemsi.commons.util.BaseRuntimeException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			throw Exceptions.server("failed-to-clear-redis").withCause(ex).get();
		}
	}

	@Override
	public void fillDetails(List<Map<String, Object>> steps) {
		Map<String, Object> props = new HashMap<>();
		props.put("type", ClearRedis.class.getSimpleName());
		if (config != null && objectMapper != null) {
			Map<String, Object> configMap = objectMapper.convertValue(config, new TypeReference<Map<String, Object>>() {});
			maskSecret(configMap, "password");
			maskSecret(configMap, "sentinelPassword");
			props.put("config", configMap);
		} else if (config != null) {
			props.put("config", maskedConfigCopy(config));
		}
		steps.add(props);
	}

	private Map<String, Object> maskedConfigCopy(ClearRedisConfig source) {
		Map<String, Object> map = new HashMap<>();
		map.put("mode", source.getMode());
		map.put("host", source.getHost());
		map.put("port", source.getPort());
		map.put("sentinels", source.getSentinels());
		map.put("masterName", source.getMasterName());
		map.put("sentinelUsername", source.getSentinelUsername());
		map.put("sentinelPassword", mask(source.getSentinelPassword()));
		map.put("username", source.getUsername());
		map.put("password", mask(source.getPassword()));
		map.put("useEnvVar", source.getUseEnvVar());
		map.put("tls", source.getTls());
		map.put("connectTimeoutMs", source.getConnectTimeoutMs());
		map.put("readTimeoutMs", source.getReadTimeoutMs());
		map.put("clearMode", source.getClearMode());
		map.put("database", source.getDatabase());
		map.put("patterns", source.getPatterns());
		map.put("scanCount", source.getScanCount());
		map.put("dryRun", source.getDryRun());
		return map;
	}

	private void maskSecret(Map<String, Object> configMap, String key) {
		Object value = configMap.get(key);
		if (value instanceof String str && !StringUtils.isEmptyOrNull(str)) {
			configMap.put(key, "*****");
		}
	}

	private String mask(String value) {
		return !StringUtils.isEmptyOrNull(value) ? "*****" : value;
	}
}
