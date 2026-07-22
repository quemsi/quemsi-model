package com.quemsi.model.flow.db.sqlserver;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariConfig;

public class DatasourceFactorySqlserverBatchPropsTest {

	@Test
	public void applySqlServerBatchDataSourceProperties_enablesBulkCopy() {
		HikariConfig config = new HikariConfig();
		DatasourceFactorySqlserver.applySqlServerBatchDataSourceProperties(config);
		assertThat(config.getDataSourceProperties().getProperty("useBulkCopyForBatchInsert"), equalTo("true"));
	}

	@Test
	public void buildMultiTableDropSql_quotesAndJoins() {
		assertThat(DDLServiceSqlserver.buildMultiTableDropSql(), nullValue());
		assertThat(DDLServiceSqlserver.buildMultiTableDropSql((String[]) null), nullValue());
		assertThat(DDLServiceSqlserver.buildMultiTableDropSql("dbo.Orders", "dbo.Order Details"),
			equalTo("DROP TABLE IF EXISTS [dbo].[Orders], [dbo].[Order Details]"));
	}
}
