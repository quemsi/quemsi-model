package com.biddflux.model.flow.factories;

import java.util.Map;
import java.util.function.Function;

import com.biddflux.model.flow.db.DataSourceFactory;
import com.biddflux.model.flow.db.sql.SqlParser;
import com.biddflux.model.flow.out.MySqlDb;
import com.biddflux.model.flow.out.Storage;
import com.fasterxml.jackson.databind.JsonNode;

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
			});
}
