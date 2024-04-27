package com.biddflux.model.flow.in;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Properties;

import com.biddflux.commons.util.Exceptions;
import com.biddflux.commons.util.FileResource;
import com.biddflux.model.flow.DataPackageFileResource;
import com.biddflux.model.flow.FlowContext;
import com.biddflux.model.flow.db.mysql.DataSourceFactoryMySql;
import com.smattme.MysqlExportService;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MySqlBackup implements Source{
	@Setter
	private MySqlBackupProperties dbProperties;
	@Setter
	private DataSourceFactoryMySql datasource;
	
	@Override
	public void execute(FlowContext context) {
		MysqlExportService mysqlExportService = new MysqlExportService(toMysqlProperties());
		try {
			mysqlExportService.export();
			log.debug("db backup file created {}", mysqlExportService.getGeneratedZipFile());
			String sql = mysqlExportService.getGeneratedSql();
			String fileName = context.getFlow().getData().getName() + ".sql";
			FileResource file = new FileResource(null, fileName, fileName, "text/sql", false, sql.length(), new ByteArrayInputStream(sql.getBytes()));
			DataPackageFileResource dp = new DataPackageFileResource(file);
			context.getDataPackages().add(dp);
			context.setDeleteAfterwards(true);
		} catch (Exception e) {
			context.logError("error exporting from mysql", e);
			throw Exceptions.server("error exporting from mysql").withCause(e).get();
		}
	}
	
	public Properties toMysqlProperties() {
		Properties p = new Properties();
		p.put(MysqlExportService.DB_NAME, this.datasource.getDbName());
		p.put(MysqlExportService.JDBC_CONNECTION_STRING, this.datasource.getUrl());
		p.put(MysqlExportService.DB_USERNAME, this.datasource.getUsername());
		p.put(MysqlExportService.DB_PASSWORD, this.datasource.getPassword());
		p.put(MysqlExportService.PRESERVE_GENERATED_SQL_FILE, toStr(true));
		p.put(MysqlExportService.PRESERVE_GENERATED_ZIP, toStr(false));
		p.put(MysqlExportService.TEMP_DIR, dbProperties.getTempDir());
		p.put(MysqlExportService.SQL_FILE_NAME, this.datasource.getName());
		
		return p;
	}
	private String toStr(Boolean b) {
		return b?"True":"False";
	}
	
	@Override
	public void fillDetails(Map<String, Object> steps) {
		steps.put("datasource", this.datasource.getName());
		steps.put("type", MySqlBackup.class.getSimpleName());
	}
}
