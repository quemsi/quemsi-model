package com.quemsi.model.flow.db.mysql;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariConfig;

public class DataSourceFactoryMySqlBatchPropsTest {

	@Test
	public void applyMysqlBatchDataSourceProperties_setsRewriteAndDisablesServerPrep() {
		HikariConfig config = new HikariConfig();
		DataSourceFactoryMySql.applyMysqlBatchDataSourceProperties(config);

		assertThat(config.getDataSourceProperties().getProperty("rewriteBatchedStatements"), equalTo("true"));
		assertThat(config.getDataSourceProperties().getProperty("useServerPrepStmts"), equalTo("false"));
		assertThat(config.getDataSourceProperties().getProperty("allowMultiQueries"), equalTo("true"));
		assertThat(config.getDataSourceProperties().getProperty("yearIsDateType"), equalTo("false"));
	}
}
