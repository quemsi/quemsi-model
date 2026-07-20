package com.quemsi.model.flow.db.postgres;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariConfig;

public class DatasourceFactoryPostgresBatchPropsTest {

	@Test
	public void sqlForEnumTypes_selectsPgEnumLabels() {
		String sql = DatasourceFactoryPostgres.SQL_FOR_ENUM_TYPES;
		assertThat(sql.contains("pg_catalog.pg_enum"), equalTo(true));
		assertThat(sql.contains("typtype = 'e'"), equalTo(true));
	}

	@Test
	public void sqlForDomainAndTriggers_present() {
		assertThat(DatasourceFactoryPostgres.SQL_FOR_DOMAIN_TYPES.contains("typtype = 'd'"), equalTo(true));
		assertThat(DatasourceFactoryPostgres.SQL_FOR_DOMAIN_COLUMNS.contains("typtype = 'd'"), equalTo(true));
		assertThat(DatasourceFactoryPostgres.SQL_FOR_TRIGGERS.contains("pg_get_triggerdef"), equalTo(true));
		assertThat(DatasourceFactoryPostgres.SQL_FOR_TRIGGERS.contains("tgisinternal"), equalTo(true));
	}

	@Test
	public void applyPostgresBatchDataSourceProperties_setsRewriteBatchedInserts() {
		HikariConfig config = new HikariConfig();
		DatasourceFactoryPostgres.applyPostgresBatchDataSourceProperties(config);

		assertThat(config.getDataSourceProperties().getProperty("reWriteBatchedInserts"), equalTo("true"));
	}

	@Test
	public void sqlForViewFunctions_isolatesAggregatesFromPgGetFunctiondef() {
		String sql = DatasourceFactoryPostgres.SQL_FOR_VIEW_FUNCTIONS;
		assertThat(sql.contains("as materialized"), equalTo(true));
		assertThat(sql.contains("prokind in ('f', 'p')"), equalTo(true));
		assertThat(sql.contains("CREATE AGGREGATE"), equalTo(true));
		assertThat(sql.contains("pg_catalog.pg_aggregate"), equalTo(true));
	}
}
