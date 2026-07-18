package com.quemsi.model.flow.db.postgres;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariConfig;

public class DatasourceFactoryPostgresBatchPropsTest {

	@Test
	public void applyPostgresBatchDataSourceProperties_setsRewriteBatchedInserts() {
		HikariConfig config = new HikariConfig();
		DatasourceFactoryPostgres.applyPostgresBatchDataSourceProperties(config);

		assertThat(config.getDataSourceProperties().getProperty("reWriteBatchedInserts"), equalTo("true"));
	}
}
