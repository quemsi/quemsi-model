package com.quemsi.model.flow.factories;

import java.util.Map;
import java.util.function.Function;
import java.util.HashMap;

import org.springframework.beans.factory.annotation.Autowired;

import com.quemsi.commons.util.JsonUtils;
import com.quemsi.model.flow.db.mysql.DataSourceFactoryMySql;
import com.quemsi.model.flow.in.MySqlBackup;
import com.quemsi.model.flow.in.MySqlBackupProperties;
import com.quemsi.model.flow.in.RdbmsBackup;
import com.quemsi.model.flow.in.Source;
import com.quemsi.model.flow.in.StoredData;
import com.quemsi.model.flow.out.Storage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import lombok.Getter;

public class SourceFactory extends AbstractFactory<Source>{
	@Autowired
	private ObjectMapper objectMapper;
	@Autowired
	private JsonUtils jsonUtils;

	@SuppressWarnings("unchecked")
	@Getter
	private Map<String, Function<JsonNode, Source>> builders = Map.of(
			"RdbmsBackup", node -> {
				String datasource = node.findValue("datasource").asText(null);
				int batchSize = jsonUtils.asInteger(node.findValue("batchSize"), 100);
				int parallelism = jsonUtils.asInteger(node.findValue("parallelism"), 1);
				RdbmsBackup s = new RdbmsBackup();
				s.setBatchSize(batchSize);
				s.setParallelism(parallelism);
				s.setDatasource(context.getBean(datasource, DataSourceFactoryMySql.class));
				s.setObjectMapper(context.getBean(ObjectMapper.class));
				return s;
			},
			"MySqlBackup", node -> {
				String datasource = node.findValue("datasource").asText(null);
				MySqlBackup s = new MySqlBackup();
				s.setDatasource(context.getBean(datasource, DataSourceFactoryMySql.class));
				s.setDbProperties(context.getBean(MySqlBackupProperties.class));
				return s;
			},
			"StoredData", node -> {
 				StoredData sd = new StoredData();
				sd.setStorage(context.getBean(node.get("storage").asText(), Storage.class));
				String version = node.get("version").asText();
				sd.setVersion(version);
				JsonNode tagsNode = node.get("tags");
				Map<String, String> tags = new HashMap<>();
				if(tagsNode.isArray()){
					ArrayNode tagsArr = (ArrayNode) tagsNode;
					for(int i=0; i < tagsArr.size(); i++){
						JsonNode tNode = tagsArr.get(i);
						String tName = tNode.findValue("name").asText(null);
						String tValue = tNode.findValue("value").asText(null);
						if(tName != null && tValue != null){
							tags.put(tName, tValue);
						}
					}
				}else{
					tags = objectMapper.convertValue(tagsNode, Map.class);
				}
				sd.setTags(tags);
				return sd;
			}
			);
}
