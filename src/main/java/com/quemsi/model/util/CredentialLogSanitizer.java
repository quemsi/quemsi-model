package com.quemsi.model.util;

import java.io.IOException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.quemsi.commons.util.SecretMask;
import com.quemsi.model.dto.AgentModel;
import com.quemsi.model.dto.AgentModel.AWSS3Drive;
import com.quemsi.model.dto.AgentModel.AzureBlobDrive;
import com.quemsi.model.dto.AgentModel.Datasource;
import com.quemsi.model.dto.ClearRedisConfig;
import com.quemsi.model.dto.agent.TestAWSS3Drive;
import com.quemsi.model.dto.agent.TestAzureBlobDrive;
import com.quemsi.model.dto.agent.TestDatasource;
import com.quemsi.model.dto.agent.PreviewSubset;
import com.quemsi.model.dto.agent.TestRedis;
import com.quemsi.model.dto.agent.UpdateAgentModel;

/**
 * Deep-copies agent payloads for logging with credentials masked
 * (literals → {@link SecretMask#MASKED}; env-var names kept when {@code useEnvVar}).
 */
public final class CredentialLogSanitizer {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private CredentialLogSanitizer() {
    }

    public static AgentModel copyMasked(AgentModel source) {
        if (source == null) {
            return null;
        }
        try {
            AgentModel copy = MAPPER.readValue(MAPPER.writeValueAsBytes(source), AgentModel.class);
            maskAgentModelInPlace(copy);
            return copy;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to copy AgentModel for logging: " + e.getMessage(), e);
        }
    }

    public static UpdateAgentModel copyMasked(UpdateAgentModel source) {
        if (source == null) {
            return null;
        }
        return new UpdateAgentModel(source.getAgentId(), copyMasked(source.getUpdatedModel()));
    }

    public static TestDatasource copyMasked(TestDatasource source) {
        if (source == null) {
            return null;
        }
        Datasource ds = source.getDatasource();
        Datasource maskedDs = null;
        if (ds != null) {
            try {
                maskedDs = MAPPER.readValue(MAPPER.writeValueAsBytes(ds), Datasource.class);
                maskDatasourceInPlace(maskedDs);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to copy Datasource for logging", e);
            }
        }
        return TestDatasource.builder()
            .agentId(source.getAgentId())
            .correlationId(source.getCorrelationId())
            .timeoutMilis(source.getTimeoutMilis())
            .datasource(maskedDs)
            .build();
    }

    public static PreviewSubset copyMasked(PreviewSubset source) {
        if (source == null) {
            return null;
        }
        Datasource ds = source.getDatasource();
        Datasource maskedDs = null;
        if (ds != null) {
            try {
                maskedDs = MAPPER.readValue(MAPPER.writeValueAsBytes(ds), Datasource.class);
                maskDatasourceInPlace(maskedDs);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to copy Datasource for logging", e);
            }
        }
        return PreviewSubset.builder()
            .agentId(source.getAgentId())
            .correlationId(source.getCorrelationId())
            .timeoutMilis(source.getTimeoutMilis())
            .datasource(maskedDs)
            .subset(source.getSubset())
            .build();
    }

    public static TestAWSS3Drive copyMasked(TestAWSS3Drive source) {
        if (source == null) {
            return null;
        }
        boolean useEnv = source.isUseEnvVar();
        return TestAWSS3Drive.builder()
            .agentId(source.getAgentId())
            .correlationId(source.getCorrelationId())
            .timeoutMilis(source.getTimeoutMilis())
            .accessKey(SecretMask.forLog(source.getAccessKey(), useEnv))
            .secretKey(SecretMask.forLog(source.getSecretKey(), useEnv))
            .region(source.getRegion())
            .bucketName(source.getBucketName())
            .useEnvVar(useEnv)
            .build();
    }

    public static TestAzureBlobDrive copyMasked(TestAzureBlobDrive source) {
        if (source == null) {
            return null;
        }
        boolean useEnv = source.isUseEnvVar();
        return TestAzureBlobDrive.builder()
            .agentId(source.getAgentId())
            .correlationId(source.getCorrelationId())
            .timeoutMilis(source.getTimeoutMilis())
            .accountName(source.getAccountName())
            .accountKey(SecretMask.forLog(source.getAccountKey(), useEnv))
            .useEnvVar(useEnv)
            .build();
    }

    public static TestRedis copyMasked(TestRedis source) {
        if (source == null) {
            return null;
        }
        boolean useEnv = source.isUseEnvVar();
        return TestRedis.builder()
            .agentId(source.getAgentId())
            .correlationId(source.getCorrelationId())
            .timeoutMilis(source.getTimeoutMilis())
            .mode(source.getMode())
            .host(source.getHost())
            .port(source.getPort())
            .sentinels(source.getSentinels())
            .masterName(source.getMasterName())
            .sentinelUsername(SecretMask.forLog(source.getSentinelUsername(), useEnv))
            .sentinelPassword(SecretMask.forLog(source.getSentinelPassword(), useEnv))
            .username(SecretMask.forLog(source.getUsername(), useEnv))
            .password(SecretMask.forLog(source.getPassword(), useEnv))
            .useEnvVar(useEnv)
            .tls(source.isTls())
            .connectTimeoutMs(source.getConnectTimeoutMs())
            .readTimeoutMs(source.getReadTimeoutMs())
            .database(source.getDatabase())
            .build();
    }

    public static void maskClearRedisConfigInPlace(ClearRedisConfig config) {
        if (config == null) {
            return;
        }
        boolean useEnv = Boolean.TRUE.equals(config.getUseEnvVar());
        config.setSentinelUsername(SecretMask.forLog(config.getSentinelUsername(), useEnv));
        config.setSentinelPassword(SecretMask.forLog(config.getSentinelPassword(), useEnv));
        config.setUsername(SecretMask.forLog(config.getUsername(), useEnv));
        config.setPassword(SecretMask.forLog(config.getPassword(), useEnv));
    }

    public static void maskAgentModelInPlace(AgentModel model) {
        if (model == null) {
            return;
        }
        if (model.getDatasources() != null) {
            for (Datasource ds : model.getDatasources()) {
                maskDatasourceInPlace(ds);
            }
        }
        if (model.getAzureBlobDrives() != null) {
            for (AzureBlobDrive drive : model.getAzureBlobDrives()) {
                if (drive == null) {
                    continue;
                }
                drive.setAccountKey(SecretMask.forLog(drive.getAccountKey(), drive.isUseEnvVar()));
            }
        }
        if (model.getAwsS3Drives() != null) {
            for (AWSS3Drive drive : model.getAwsS3Drives()) {
                if (drive == null) {
                    continue;
                }
                boolean useEnv = drive.isUseEnvVar();
                drive.setAccessKey(SecretMask.forLog(drive.getAccessKey(), useEnv));
                drive.setSecretKey(SecretMask.forLog(drive.getSecretKey(), useEnv));
            }
        }
    }

    private static void maskDatasourceInPlace(Datasource ds) {
        if (ds == null) {
            return;
        }
        boolean useEnv = ds.isUseEnvVar();
        ds.setUsername(SecretMask.forLog(ds.getUsername(), useEnv));
        ds.setPassword(SecretMask.forLog(ds.getPassword(), useEnv));
    }
}
