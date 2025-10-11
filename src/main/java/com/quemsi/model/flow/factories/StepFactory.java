package com.quemsi.model.flow.factories;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.quemsi.commons.util.FileNameUtil;
import com.quemsi.commons.util.JsonUtils;
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
import com.quemsi.model.flow.process.SchemaMapping;

import lombok.Getter;

public class StepFactory extends AbstractFactory<Step>{
	@Autowired
	private SourceFactory sourceFactory;
	@Autowired
	private StorageFactory storageFactory;
	@Autowired
	private JsonUtils jsonUtils;
	
	@Getter
	private Map<String, Function<JsonNode, Step>> builders = Map.of(
			"StopReplica", node -> {
				String datasource = node.findValue("datasource").asText(null);
				StopReplica s = new StopReplica();
				s.setDatasource(context.getBean(datasource, DataSourceFactory.class));
				return s;
			},
			"StartReplica", node -> {
				String datasource = node.findValue("datasource").asText(null);
				StartReplica s = new StartReplica();
				s.setDatasource(context.getBean(datasource, DataSourceFactory.class));
				return s;
			},
			"From", node -> {
				From s = new From();
				JsonNode sourceNode = node.get("source");
				s.setSource(sourceFactory.from(sourceNode));
				return s;
			},
			"To", node -> {
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
			},
			"Zip", node -> {
				Zip zip = new Zip();
				return zip;
			},
			"Unzip", node -> {
				Unzip unzip = new Unzip();
				unzip.setUtil(context.getBean(FileNameUtil.class));
				return unzip;
			},
			"ClearTables", node -> {
				String datasource = node.findValue("datasource").asText(null);
				ClearTables clearTables = new ClearTables();
				boolean all = jsonUtils.asBoolean(node.findValue("all"), true);
				clearTables.setAll(all);
				LinkedList<String> tables = jsonUtils.asLinkedList(node.get("tables"));
				clearTables.setTables(tables);
				clearTables.setDatasource(context.getBean(datasource, DataSourceFactory.class));
				return clearTables;
			},
			"MySqlScript", node ->  {
				MySqlScript mScript = new MySqlScript();
				String datasource = node.findValue("datasource").asText(null);
				mScript.setDatasourceFactory(context.getBean(datasource, DataSourceFactory.class));
				String script = node.findValue("script").asText();
				mScript.setScript(script);
				mScript.setSqlParser(context.getBean(SqlParser.class));
				return mScript;
			},
			"DropTables", node ->  {
				DropTables dropTables = new DropTables();
				String datasource = node.findValue("datasource").asText(null);
				dropTables.setDatasource(context.getBean(datasource, DataSourceFactory.class));
				boolean all = jsonUtils.asBoolean(node.findValue("all"), true);
				dropTables.setAll(all);
				LinkedList<String> tables = jsonUtils.asLinkedList(node.get("tables"));
				dropTables.setTables(tables);
				dropTables.setSqlParser(context.getBean(SqlParser.class));
				return dropTables;
			},
			"SchemaMapping", node -> {
				SchemaMapping schemaMapping = new SchemaMapping();
				schemaMapping.setSourceSchema(node.findValue("sourceSchema").asText(null));
				schemaMapping.setTargetSchema(node.findValue("targetSchema").asText(null));
				return schemaMapping;
			}
			);
}
