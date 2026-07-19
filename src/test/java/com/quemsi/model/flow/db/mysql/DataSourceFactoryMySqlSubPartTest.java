package com.quemsi.model.flow.db.mysql;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;

public class DataSourceFactoryMySqlSubPartTest {

	@Test
	public void toIntegerOrNull_acceptsIntegerAndLong() {
		assertThat(DataSourceFactoryMySql.toIntegerOrNull(null), nullValue());
		assertThat(DataSourceFactoryMySql.toIntegerOrNull(100), equalTo(100));
		assertThat(DataSourceFactoryMySql.toIntegerOrNull(100L), equalTo(100));
	}
}
