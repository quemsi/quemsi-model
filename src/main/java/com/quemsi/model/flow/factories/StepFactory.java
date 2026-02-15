package com.quemsi.model.flow.factories;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.commons.util.FileNameUtil;
import com.quemsi.commons.util.JsonUtils;
import com.quemsi.model.dto.MaskColumn;
import com.quemsi.model.flow.From;
import com.quemsi.model.flow.Step;
import com.quemsi.model.flow.To;
import com.quemsi.model.flow.db.ClearTables;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.DropTables;
import com.quemsi.model.flow.db.mysql.MySqlScript;
import com.quemsi.model.flow.db.mysql.StartReplica;
import com.quemsi.model.flow.db.mysql.StopReplica;
import com.quemsi.model.flow.db.sql.SqlParser;
import com.quemsi.model.flow.file.Unzip;
import com.quemsi.model.flow.file.Zip;
import com.quemsi.model.flow.out.Storage;
import com.quemsi.model.flow.process.MaskColumns;
import com.quemsi.model.flow.process.SchemaMapping;
import com.quemsi.model.flow.process.UpdateSchema;
import com.quemsi.model.flow.process.UpdateSequences;

import lombok.Getter;

public class StepFactory extends AbstractFactory<Step>{
	@Autowired
	private SourceFactory sourceFactory;
	@Autowired
	private StorageFactory storageFactory;
	@Autowired
	private JsonUtils jsonUtils;
	
	@Getter
	private Map<String, Function<JsonNode, Step>> builders = Map.ofEntries(
			Map.entry("StopReplica", (Function<JsonNode, Step>) node -> {
				String datasource = node.findValue("datasource").asText(null);
				StopReplica s = new StopReplica();
				s.setDatasource(context.getBean(datasource, DataSourceFactory.class));
				return s;
			}),
			Map.entry("StartReplica", (Function<JsonNode, Step>) node -> {
				String datasource = node.findValue("datasource").asText(null);
				StartReplica s = new StartReplica();
				s.setDatasource(context.getBean(datasource, DataSourceFactory.class));
				return s;
			}),
			Map.entry("From", (Function<JsonNode, Step>) node -> {
				From s = new From();
				JsonNode sourceNode = node.get("source");
				s.setSource(sourceFactory.from(sourceNode));
				return s;
			}),
			Map.entry("To", (Function<JsonNode, Step>) node -> {
				To s = new To();
				JsonNode targetsNode = node.get("targets");
				List<Storage> targets = new LinkedList<>();
				if(targetsNode != null && targetsNode.isArray()) {
					for(JsonNode tNode : targetsNode){
						Storage target = storageFactory.from(tNode);
						targets.add(target);
					}
				}
				s.setTargets(targets);
				return s;
			}),
			Map.entry("Zip", (Function<JsonNode, Step>) node -> {
				Zip zip = new Zip();
				return zip;
			}),
			Map.entry("Unzip", (Function<JsonNode, Step>) node -> {
				Unzip unzip = new Unzip();
				unzip.setUtil(context.getBean(FileNameUtil.class));
				return unzip;
			}),
			Map.entry("ClearTables", (Function<JsonNode, Step>) node -> {
				String datasource = node.findValue("datasource").asText(null);
				ClearTables clearTables = new ClearTables();
				boolean all = jsonUtils.asBoolean(node.findValue("all"), true);
				clearTables.setAll(all);
				LinkedList<String> tables = jsonUtils.asLinkedList(node.get("tables"));
				clearTables.setTables(tables);
				clearTables.setDatasource(context.getBean(datasource, DataSourceFactory.class));
				return clearTables;
			}),
			Map.entry("MySqlScript", (Function<JsonNode, Step>) node ->  {
				MySqlScript mScript = new MySqlScript();
				String datasource = node.findValue("datasource").asText(null);
				mScript.setDatasourceFactory(context.getBean(datasource, DataSourceFactory.class));
				String script = node.findValue("script").asText();
				mScript.setScript(script);
				mScript.setSqlParser(context.getBean(SqlParser.class));
				return mScript;
			}),
			Map.entry("DropTables", (Function<JsonNode, Step>) node ->  {
				DropTables dropTables = new DropTables();
				String datasource = node.findValue("datasource").asText(null);
				dropTables.setDatasource(context.getBean(datasource, DataSourceFactory.class));
				boolean all = jsonUtils.asBoolean(node.findValue("all"), true);
				dropTables.setAll(all);
				LinkedList<String> tables = jsonUtils.asLinkedList(node.get("tables"));
				dropTables.setTables(tables);
				dropTables.setSqlParser(context.getBean(SqlParser.class));
				return dropTables;
			}),
			Map.entry("SchemaMapping", (Function<JsonNode, Step>) node -> {
				SchemaMapping schemaMapping = new SchemaMapping();
				schemaMapping.setSourceSchema(node.findValue("sourceSchema").asText(null));
				schemaMapping.setTargetSchema(node.findValue("targetSchema").asText(null));
				return schemaMapping;
			}),
			Map.entry("MaskColumns", (Function<JsonNode, Step>) node -> {
				MaskColumns maskColumns = new MaskColumns();
				ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
				maskColumns.setObjectMapper(objectMapper);
				JsonNode configNode = node.get("config");
				if(configNode != null) {
					MaskColumn config = objectMapper.convertValue(configNode, MaskColumn.class);
					// Set default parallelism if not provided or invalid
					if(config.getParallelism() <= 0) {
						config.setParallelism(10);
					}
					maskColumns.setConfig(config);
					maskColumns.setParallelism(config.getParallelism());
				} else {
					// If no config, set default parallelism
					maskColumns.setParallelism(10);
				}
				return maskColumns;
			}),
			Map.entry("UpdateSequences", (Function<JsonNode, Step>) node -> {
				UpdateSequences updateSequences = new UpdateSequences();
				ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
				updateSequences.setObjectMapper(objectMapper);
				String datasource = node.findValue("datasource").asText(null);
				updateSequences.setDatasourceFactory(context.getBean(datasource, DataSourceFactory.class));
				JsonNode configNode = node.get("config");
				if(configNode != null) {
					com.quemsi.model.dto.UpdateSequences config = objectMapper.convertValue(configNode, com.quemsi.model.dto.UpdateSequences.class);
					updateSequences.setConfig(config);
				}
				return updateSequences;
			}),
			Map.entry("UpdateSchema", (Function<JsonNode, Step>) node -> {
				UpdateSchema updateSchema = new UpdateSchema();
				ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
				updateSchema.setObjectMapper(objectMapper);
				String datasource = node.findValue("datasource").asText(null);
				updateSchema.setDatasourceFactory(context.getBean(datasource, DataSourceFactory.class));
				JsonNode configNode = node.get("config");
				if(configNode != null) {
					com.quemsi.model.dto.UpdateSchemaConfig config = objectMapper.convertValue(configNode, com.quemsi.model.dto.UpdateSchemaConfig.class);
					updateSchema.setConfig(config);
				}
				return updateSchema;
			})
			);
}
