package com.quemsi.model.flow.factories;

import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.SqlParser;
import com.quemsi.model.flow.out.MySqlDb;
import com.quemsi.model.flow.out.RdbmsTarget;
import com.quemsi.model.flow.out.Storage;

import lombok.Getter;

public class StorageFactory extends AbstractFactory<Storage>{
	@Getter
	private Map<String, Function<JsonNode, Storage>> builders = Map.of(
			"Storage", node ->{
				String name = node.get("name").asText(null);
				return context.getBean(name, Storage.class);
			},
			"MySqlDb", node -> {
				String datasource = node.get("datasource").asText(null);
				MySqlDb mySqlDb = new MySqlDb();
				mySqlDb.setDatasourceFactory(context.getBean(datasource, DataSourceFactory.class));
				mySqlDb.setSqlParser(context.getBean(SqlParser.class));
				return mySqlDb;
			},
			"RdbmsTarget", node -> {
				String datasource = node.get("datasource").asText(null);
				RdbmsTarget rdbmsTarget = new RdbmsTarget();
				rdbmsTarget.setDatasourceFactory(context.getBean(datasource, DataSourceFactory.class));
				rdbmsTarget.setObjectMapper(context.getBean(ObjectMapper.class));
				return rdbmsTarget;
			});
}
