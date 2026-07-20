package com.quemsi.model.flow.db.sqlserver;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;

import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbDomainType;
import com.quemsi.model.flow.db.sql.DbFunction;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sql.DbTrigger;

public class DDLServiceSqlserverRoutinesTypesTest {

	@Test
	public void createAliasTypeSql_includesBaseTypeAndNotNull() {
		DbDomainType tid = DbDomainType.builder()
			.schema("dbo")
			.name("tid")
			.baseType("varchar(6)")
			.notNull(true)
			.build();
		assertThat(DDLServiceSqlserver.createAliasTypeSql(tid),
			equalTo("CREATE TYPE [dbo].[tid] FROM varchar(6) NOT NULL"));
	}

	@Test
	public void dropAndCreateRoutineSql() {
		DbFunction proc = DbFunction.builder()
			.schema("dbo")
			.name("byroyalty")
			.routineType(DbFunction.TYPE_PROCEDURE)
			.definition("CREATE PROCEDURE dbo.byroyalty @percentage int AS SELECT 1")
			.build();
		assertThat(DDLServiceSqlserver.dropRoutineSql(proc), equalTo("DROP PROCEDURE IF EXISTS [dbo].[byroyalty]"));
		assertThat(DDLServiceSqlserver.createRoutineSql(proc), containsString("CREATE PROCEDURE"));

		DbFunction fn = DbFunction.builder()
			.schema("dbo")
			.name("title_id_fn")
			.routineType(DbFunction.TYPE_FUNCTION)
			.definition("CREATE FUNCTION dbo.title_id_fn() RETURNS int AS BEGIN RETURN 1 END")
			.build();
		assertThat(DDLServiceSqlserver.dropRoutineSql(fn), equalTo("DROP FUNCTION IF EXISTS [dbo].[title_id_fn]"));
	}

	@Test
	public void dropTriggerSql_bracketsSchemaAndName() {
		DbTrigger trigger = DbTrigger.builder()
			.schema("dbo")
			.tableName("employee")
			.name("employee_insupd")
			.definition("CREATE TRIGGER employee_insupd ON employee FOR INSERT AS SELECT 1")
			.build();
		assertThat(DDLServiceSqlserver.dropTriggerSql(trigger), equalTo("DROP TRIGGER IF EXISTS [dbo].[employee_insupd]"));
	}

	@Test
	public void formatAliasBaseType_handlesCharAndNvarcharLengths() {
		assertThat(DatasourceFactorySqlserver.formatAliasBaseType("varchar", 6, 0, 0), equalTo("varchar(6)"));
		assertThat(DatasourceFactorySqlserver.formatAliasBaseType("nchar", 20, 0, 0), equalTo("nchar(10)"));
		assertThat(DatasourceFactorySqlserver.formatAliasBaseType("decimal", 5, 4, 2), equalTo("decimal(4,2)"));
		assertThat(DatasourceFactorySqlserver.formatAliasBaseType("int", 4, 10, 0), equalTo("int"));
	}

	@Test
	public void applyAliasTypesToColumns_switchesDataTypeToUdtName() {
		DbModel model = new DbModel();
		model.getDomainTypes().add(DbDomainType.builder().schema("dbo").name("tid").baseType("varchar(6)").notNull(true).build());
		DbTable titles = new DbTable("dbo", "titles");
		titles.addColumn(DbColumn.builder()
			.name("title_id")
			.dataType("varchar")
			.columnType("tid")
			.maxLength(6)
			.ordinalPosition(1)
			.nullable(false)
			.build());
		model.getTables().put(titles.qualifiedName(), titles);

		DatasourceFactorySqlserver.applyAliasTypesToColumns(model);

		DbColumn col = titles.findColumn("title_id").orElseThrow();
		assertThat(col.getDataType(), equalTo("tid"));
		assertThat(col.getMaxLength(), nullValue());
	}
}
