package com.quemsi.model.flow.db.sqlserver;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.quemsi.model.flow.db.sqlserver.DDLServiceSqlserver.ForeignKeyOnTable;

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

	@Test
	public void foreignKeyDropSql_dropsKeysThatTouchTablesBeingDropped() {
		List<ForeignKeyOnTable> keys = List.of(
			new ForeignKeyOnTable("HR", "EMPLOYEES", "EMP_DEPT_FK", "HR", "DEPARTMENTS"),
			new ForeignKeyOnTable("HR", "DEPARTMENTS", "DEPT_MGR_FK", "HR", "EMPLOYEES"),
			new ForeignKeyOnTable("dbo", "Widget", "FK_Widget_Dept", "HR", "DEPARTMENTS"),
			new ForeignKeyOnTable("dbo", "Keep", "FK_Keep_Other", "dbo", "Other"));

		assertThat(DDLServiceSqlserver.foreignKeyDropSql(keys, "HR.DEPARTMENTS", "HR.EMPLOYEES"), contains(
			"ALTER TABLE [HR].[EMPLOYEES] DROP CONSTRAINT [EMP_DEPT_FK]",
			"ALTER TABLE [HR].[DEPARTMENTS] DROP CONSTRAINT [DEPT_MGR_FK]",
			"ALTER TABLE [dbo].[Widget] DROP CONSTRAINT [FK_Widget_Dept]"));
		assertThat(DDLServiceSqlserver.foreignKeyDropSql(keys, "HR.JOBS"), empty());
	}
}
