package com.quemsi.model.flow.db;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.LogMessage;
import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.DataPackage;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.diff.DbModelDiff;
import com.quemsi.model.flow.db.sql.diff.DiffEntityType;
import com.quemsi.model.service.DbModelDiffExtractor;
import com.quemsi.model.util.CommonConstants;

import lombok.Setter;

public class DropOrClear extends AbstractStep {
	@Setter
	private DataSourceFactory datasource;
	@Setter
	private boolean all = true;
	@Setter
	private ObjectMapper objectMapper;

	@Override
	public void execute(FlowContext context) {
		datasource.assertWritable();
		try {
			DbModel sourceModel = loadBackupModel(context);
			DbModel targetModel = datasource.getDbModel(msg -> context.logStep(context.getCurrentStep(), msg));

			DbModelDiff diff = new DbModelDiffExtractor().extract(sourceModel, targetModel);
			boolean schemaChanged = diff.getOperations().stream()
				.anyMatch(op -> op.getEntityType() != DiffEntityType.SEQUENCE);

			if (schemaChanged) {
				context.logStepInfo(context.getCurrentStep(),
					LogMessage.info("Schema differs from backup, dropping all objects"));
				DropTables drop = new DropTables();
				drop.setDatasource(datasource);
				drop.setAll(true);
				drop.execute(context);
			} else {
				context.logStepInfo(context.getCurrentStep(),
					LogMessage.info("Schema matches backup, truncating tables"));
				truncateLiveTables(targetModel);
			}
		} catch (BaseRuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw Exceptions.server("error-drop-or-clear")
				.withExtra("datasource", datasource.getName())
				.withCause(e)
				.get();
		}
	}

	private void truncateLiveTables(DbModel targetModel) throws Exception {
		Set<ReferenceInfo> allFks = new LinkedHashSet<>();
		if (targetModel.getReferenceInfos() != null) {
			allFks.addAll(targetModel.getReferenceInfos());
		}
		if (targetModel.getCircularIgnore() != null) {
			allFks.addAll(targetModel.getCircularIgnore());
		}
		try (DMLService dmlService = datasource.dmlService();
			 DDLService ddlService = datasource.ddlService()) {
			if (!allFks.isEmpty()) {
				ddlService.disableConstraints(allFks);
			}
			LinkedList<String> tables = targetModel.orderedTableNames();
			if (tables != null && !tables.isEmpty()) {
				dmlService.truncateTables(tables.toArray(new String[0]));
			}
		}
	}

	private DbModel loadBackupModel(FlowContext context) {
		try {
			if (context.getBackupArchive() != null
					&& context.getBackupArchive().exists(CommonConstants.DB_MODEL_FILE_NAME)) {
				try (java.io.InputStream in = context.getBackupArchive().open(CommonConstants.DB_MODEL_FILE_NAME)) {
					DbModel dbModel = objectMapper.readValue(in, DbModel.class);
					context.logStepInfo(context.getCurrentStep(),
						LogMessage.info("Loaded DbModel from archive with {} tables", dbModel.getTables().size()));
					return dbModel;
				}
			}

			List<DataPackage> dataPackages = context.getDataPackages();
			if (dataPackages == null || dataPackages.isEmpty()) {
				throw Exceptions.notFound("unable-to-find-data-packages").get();
			}

			Map<String, DataPackage> namedPackages = dataPackages.stream()
				.collect(Collectors.toMap(DataPackage::getName, dp -> dp));

			if (!namedPackages.containsKey(CommonConstants.DB_MODEL_FILE_NAME)) {
				throw Exceptions.notFound("unable-to-find-db-model").get();
			}

			DataPackage dbModelPackage = namedPackages.get(CommonConstants.DB_MODEL_FILE_NAME);
			String dbModelJsonStr = IOUtils.toString(
				dbModelPackage.getInputStream(),
				Charset.forName("UTF-8")
			);
			DbModel dbModel = objectMapper.readValue(dbModelJsonStr, DbModel.class);
			context.logStepInfo(context.getCurrentStep(),
				LogMessage.info("Loaded DbModel from data file with {} tables", dbModel.getTables().size()));
			return dbModel;
		} catch (IOException e) {
			throw Exceptions.server("io-exception-reading-db-model").withCause(e).get();
		}
	}

	@Override
	public void fillDetails(List<Map<String, Object>> steps) {
		Map<String, Object> props = new HashMap<>();
		props.put("type", DropOrClear.class.getSimpleName());
		props.put("datasource", datasource.getName());
		props.put("all", all);
		steps.add(props);
	}
}
