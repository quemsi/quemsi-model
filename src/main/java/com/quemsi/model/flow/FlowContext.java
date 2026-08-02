package com.quemsi.model.flow;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.quemsi.commons.util.LogMessage;
import com.quemsi.model.dto.DataVersion;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.dto.FlowExecution;
import com.quemsi.model.dto.FlowExecution.FlowExecutionStep;
import com.quemsi.model.dto.FlowExecutionStatus;
import com.quemsi.model.dto.Tag;
import com.quemsi.model.flow.file.BackupArchive;
import com.quemsi.model.flow.process.DbModelProcessor;
import com.quemsi.model.util.QuemsiTemp;

import lombok.Data;

@Data
public class FlowContext {
	private boolean deleteAfterwards;
	private FlowExecution execution;
	private DataVersion dataVersion;
	private List<DataPackage> dataPackages;
	/** Staging directory for page-entry backup before Zip. */
	private Path stagingDir;
	/** Open backup zip for lazy restore reads. */
	private BackupArchive backupArchive;
	private Flow flow;
	private Map<String, String> tags;
	private List<DbModelProcessor> dbModelProcessors;
	private FlowExecutionStep currentStep;
	
	@FunctionalInterface
	public interface LogWriter {
		void log(Long agentId, Long flowExecutionId, Long flowExecutionStepId, LogMessage message);
	}
	
	private LogWriter logWriter;
	
	public FlowContext(Flow flow, Long flowExecutionId) {
		execution = new FlowExecution();
		execution.setId(flowExecutionId);
		execution.setActive(true);
		execution.setFlowId(flow.getId());
		execution.setFlowName(flow.getName());
		execution.setNumberOfSteps(flow.numberOfSteps());
		execution.setStatus(FlowExecutionStatus.SCHEDULED);
		this.tags = new HashMap<>();
		this.flow = flow;
		dataPackages = new LinkedList<>();
		dbModelProcessors = new LinkedList<>();
	}

	public void setDataVersion(DataVersion dataVersion){
		this.dataVersion = dataVersion;
		execution.setVersion(dataVersion);
	}

	/**
	 * Records the backup DB provider on the version column and as the reserved {@code db} tag
	 * so UI tag filters and tag-based version lookup both see it.
	 */
	public void recordDatasourceType(DatasourceType type) {
		if (type == null || dataVersion == null) {
			return;
		}
		dataVersion.setDatasourceType(type);
		if (tags == null) {
			tags = new HashMap<>();
		}
		tags.put(DataVersion.DB_TAG, type.name());
		dataVersion.setTags(tags.entrySet().stream()
				.map(e -> Tag.builder().name(e.getKey()).val(e.getValue()).build())
				.toList());
	}
	
	public boolean inError() {
		return FlowExecutionStatus.FAILED.equals(execution.getStatus());
	}
	public Long executionVersion(){
		return this.dataVersion.getId();
	}
	public void logError(FlowExecutionStep step, String tag, Throwable e) {
		if(step != null){
			step.setStatus(FlowExecutionStatus.FAILED);
			if (logWriter != null && execution != null && execution.getId() != null && step.getId() != null) {
				logWriter.log(null, execution.getId(), step.getId(), LogMessage.errorWithCause(tag, e));
			}
		}
	}
	public void logError(String tag, Throwable e) {
		execution.setStatus(FlowExecutionStatus.FAILED);
		if (logWriter != null && execution.getId() != null) {
			logWriter.log(null, execution.getId(), null, LogMessage.errorWithCause(tag, e));
		}
	}
	
	public void logInfo(String message) {
		if (logWriter != null && execution != null && execution.getId() != null) {
			logWriter.log(null, execution.getId(), null, LogMessage.info(message));
		}
	}
	
	public void logWarn(String message) {
		if (logWriter != null && execution != null && execution.getId() != null) {
			logWriter.log(null, execution.getId(), null, LogMessage.warn(message));
		}
	}
	
	public void logError(String message) {
		if (logWriter != null && execution != null && execution.getId() != null) {
			logWriter.log(null, execution.getId(), null, LogMessage.error(message));
		}
	}
	
	public void logStep(FlowExecutionStep step, LogMessage message) {
		if (logWriter != null && execution != null && execution.getId() != null && step != null && step.getId() != null) {
			logWriter.log(null, execution.getId(), step.getId(), message);
		}
	}

	public void logStepInfo(FlowExecutionStep step, LogMessage message) {
		if (logWriter != null && execution != null && execution.getId() != null && step != null && step.getId() != null) {
			logWriter.log(null, execution.getId(), step.getId(), LogMessage.info(message));
		}
	}
	
	public void logStepWarn(FlowExecutionStep step, String message) {
		if (logWriter != null && execution != null && execution.getId() != null && step != null && step.getId() != null) {
			logWriter.log(null, execution.getId(), step.getId(), LogMessage.warn(message));
		}
	}
	
	public void logStepError(FlowExecutionStep step, String message) {
		if (logWriter != null && execution != null && execution.getId() != null && step != null && step.getId() != null) {
			logWriter.log(null, execution.getId(), step.getId(), LogMessage.error(message));
		}
	}
	public void logStepError(FlowExecutionStep step, String tag, Throwable e) {
		if (logWriter != null && execution != null && execution.getId() != null && step != null && step.getId() != null) {
			logWriter.log(null, execution.getId(), step.getId(), LogMessage.errorWithCause(tag, e));
		}
	}

	public void closeBackupArchiveQuietly() {
		if (backupArchive != null) {
			try {
				backupArchive.close();
			} catch (Exception ignored) {
				/* best effort */
			}
			backupArchive = null;
		}
	}

	public void clearStagingDirQuietly() {
		if (stagingDir != null) {
			QuemsiTemp.deleteRecursively(stagingDir);
			stagingDir = null;
		}
	}
}
