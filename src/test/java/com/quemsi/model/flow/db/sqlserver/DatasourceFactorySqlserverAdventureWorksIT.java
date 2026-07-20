package com.quemsi.model.flow.db.sqlserver;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbTable;

/**
 * Optional IT against AdventureWorks2022.
 * Set {@code SQLSERVER_IT_URL}, {@code SQLSERVER_IT_USER}, {@code SQLSERVER_IT_PASSWORD} to run.
 * Prefer a {@code db_datareader} user (e.g. DemoReader) — that is the regression for alias UDTs.
 */
public class DatasourceFactorySqlserverAdventureWorksIT {

	@Test
	public void getDbModel_includesMakeFlagOnProductionProduct() {
		String url = System.getenv("SQLSERVER_IT_URL");
		String user = System.getenv("SQLSERVER_IT_USER");
		String password = System.getenv("SQLSERVER_IT_PASSWORD");
		assumeTrue(url != null && !url.isBlank(), "SQLSERVER_IT_URL not set");
		assumeTrue(isDriverPresent(), "mssql-jdbc not on test classpath");

		DatasourceFactorySqlserver factory = new DatasourceFactorySqlserver();
		factory.setName("aw-it");
		factory.setUrl(url);
		factory.setUsername(user);
		factory.setPassword(password);
		factory.setSchemas(new LinkedHashSet<>(Set.of(
			"dbo", "HumanResources", "Person", "Production", "Purchasing", "Sales"
		)));

		try {
			factory.afterPropertiesSet();
		} catch (RuntimeException e) {
			assumeTrue(false, "Unable to open SQL Server IT connection: " + e.getMessage());
		}

		try {
			DbModel model = factory.getDbModel(msg -> {});
			DbTable product = model.findTable("Production.Product").orElse(null);
			assertThat(product, notNullValue());
			assertThat(product.getColumns().keySet(), hasItem("MakeFlag"));
			assertFalse(product.findColumn("MakeFlag").isEmpty());
			assertThat(product.findColumn("MakeFlag").get().getDefaultConstraintName(), notNullValue());
		} finally {
			factory.preDestroy();
		}
	}

	private static boolean isDriverPresent() {
		try {
			Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}
}
