package com.quemsi.model.flow.db.postgres;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;

public class DMLServicePostgresTruncateTest {

	@Test
	public void buildMultiTableTruncateSql_joinsNamesWithRestartIdentityCascade() {
		assertThat(DMLServicePostgres.buildMultiTableTruncateSql("public.a", "public.b", "c"),
			equalTo("TRUNCATE TABLE \"public\".\"a\", \"public\".\"b\", \"c\" RESTART IDENTITY CASCADE"));
		assertThat(DMLServicePostgres.buildMultiTableTruncateSql(), nullValue());
		assertThat(DMLServicePostgres.buildMultiTableTruncateSql((String[]) null), nullValue());
	}
}
