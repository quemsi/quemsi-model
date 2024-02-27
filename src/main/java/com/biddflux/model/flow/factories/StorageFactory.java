package com.biddflux.model.flow.factories;

import java.util.Map;
import java.util.function.Function;

import com.biddflux.model.flow.db.DataSourceFactory;
import com.biddflux.model.flow.out.GoogleDrive;
import com.biddflux.model.flow.out.Gstorage;
import com.biddflux.model.flow.out.MySqlDb;
import com.biddflux.model.flow.out.Storage;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.Getter;

public class StorageFactory extends AbstractFactory<Storage>{
	@Getter
	private Map<String, Function<JsonNode, Storage>> builders = Map.of(
			"Gstorage", node -> {
				boolean applyVersion = node.get("applyVersion").asBoolean(false);
				String rootPath = node.get("rootPath").asText(null);
				String googleDrive = node.findValue("googleDrive").asText(null);
				
				Gstorage s = new Gstorage();
				s.setApplyVersion(applyVersion);
				// s.setCheckSumProducer(context.getBean(ChecksumProducer.class));
				s.setGoogleDrive(context.getBean(googleDrive, GoogleDrive.class));
				// s.setGoogleDriveService(context.getBean(GoogleDriveManager.class));
				s.setRootPath(rootPath);
				context.getAutowireCapableBeanFactory().autowireBean(s);
				setCommonBeans(s);
				return s;
			},
			"Storage", node ->{
				String name = node.get("name").asText(null);
				return context.getBean(name, Storage.class);
			},
			"MySqlDb", node -> {
				String datasource = node.get("datasource").asText(null);
				MySqlDb mySqlDb = new MySqlDb();
				mySqlDb.setDatasourceFactory(context.getBean(datasource, DataSourceFactory.class));
				setCommonBeans(mySqlDb);
				return mySqlDb;
			});
	@Override
	protected void setCommonBeans(Storage s) {	
		context.getAutowireCapableBeanFactory().autowireBean(s);
	}
}
