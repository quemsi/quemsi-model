package com.biddflux.model.flow.factories;

import java.util.Map;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;

import com.biddflux.model.flow.db.mysql.DataSourceFactoryMySql;
import com.biddflux.model.flow.in.MySqlBackupProperties;
import com.biddflux.model.flow.in.MySqlBackup;
import com.biddflux.model.flow.in.Source;
import com.biddflux.model.flow.in.StoredData;
import com.biddflux.model.flow.out.Storage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Getter;

public class SourceFactory extends AbstractFactory<Source>{
	@Autowired
	private ObjectMapper objectMapper;
	
	@Getter
	private Map<String, Function<JsonNode, Source>> builders = Map.of(
			"MySqlBackup", node -> {
				String datasource = node.findValue("datasource").asText(null);
				MySqlBackup s = new MySqlBackup();
				s.setDatasource(context.getBean(datasource, DataSourceFactoryMySql.class));
				s.setDbProperties(context.getBean(MySqlBackupProperties.class));
				setCommonBeans(s);
				return s;
			},
			"StoredData", node -> {
 				StoredData sd = new StoredData();
				sd.setStorage(context.getBean(node.get("storage").asText(), Storage.class));
				String version = node.get("version").asText();
				sd.setVersion(version);
				JsonNode tagsNode = node.get("tags");
				@SuppressWarnings("unchecked")
				Map<String, String> tags = objectMapper.convertValue(tagsNode, Map.class);
				sd.setTags(tags);
				setCommonBeans(sd);
				return sd;
			}
			);
	@Override
	protected void setCommonBeans(Source s) {	
		context.getAutowireCapableBeanFactory().autowireBean(s);
	}
}
