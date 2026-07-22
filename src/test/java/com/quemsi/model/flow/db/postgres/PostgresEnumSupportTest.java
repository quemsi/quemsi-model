package com.quemsi.model.flow.db.postgres;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbEnumType;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbTable;

public class PostgresEnumSupportTest {

	@Test
	public void ensureEnumTypes_keepsExistingEnumTypes() {
		DbModel dbModel = new DbModel();
		dbModel.getEnumTypes().add(DbEnumType.builder()
			.schema("public")
			.name("mpaa_rating")
			.labels(List.of("G", "PG", "PG-13", "R", "NC-17"))
			.build());
		DbTable film = dbModel.addTable("film", "public");
		film.addColumn(DbColumn.builder().name("rating").columnType("mpaa_rating").dataType("mpaa_rating")
			.ordinalPosition(1).nullable(true).build());

		PostgresEnumSupport.ensureEnumTypes(dbModel);

		assertThat(dbModel.getEnumTypes(), hasSize(1));
		assertThat(dbModel.getEnumTypes().get(0).getLabels(), hasSize(5));
	}

	@Test
	public void ensureEnumTypes_reconstructsFromColumnDefault() {
		DbModel dbModel = new DbModel();
		DbTable film = dbModel.addTable("film", "public");
		film.addColumn(DbColumn.builder().name("rating").columnType("mpaa_rating").dataType("mpaa_rating")
			.ordinalPosition(1).nullable(true).columnDefault("'G'::mpaa_rating").build());

		PostgresEnumSupport.ensureEnumTypes(dbModel);

		assertThat(dbModel.getEnumTypes(), hasSize(1));
		DbEnumType enumType = dbModel.getEnumTypes().get(0);
		assertThat(enumType.getSchema(), equalTo("public"));
		assertThat(enumType.getName(), equalTo("mpaa_rating"));
		assertThat(enumType.getLabels(), contains("G"));
	}

	@Test
	public void ensureEnumTypes_failsWhenEnumMissingAndNoDefault() {
		DbModel dbModel = new DbModel();
		DbTable film = dbModel.addTable("film", "public");
		film.addColumn(DbColumn.builder().name("rating").columnType("mpaa_rating").dataType("mpaa_rating")
			.ordinalPosition(1).nullable(true).build());

		assertThrows(BaseRuntimeException.class, () -> PostgresEnumSupport.ensureEnumTypes(dbModel));
	}
}
