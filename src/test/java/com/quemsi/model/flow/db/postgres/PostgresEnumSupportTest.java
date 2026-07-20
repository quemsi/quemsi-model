package com.quemsi.model.flow.db.postgres;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.model.flow.DataPackage;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbEnumType;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbTable;

public class PostgresEnumSupportTest {

	@Test
	public void ensureEnumTypes_reconstructsFromDefaultAndData() throws Exception {
		DbModel dbModel = new DbModel();
		DbTable film = dbModel.addTable("film", "public");
		film.addColumn(DbColumn.builder().name("film_id").columnType("int4").dataType("int4")
			.ordinalPosition(1).nullable(false).build());
		film.addColumn(DbColumn.builder().name("rating").columnType("mpaa_rating").dataType("mpaa_rating")
			.ordinalPosition(2).nullable(true).columnDefault("'G'::mpaa_rating").build());

		String tableDataJson = """
			{
			  "tableName": "public.film",
			  "dataFormat": "tabular",
			  "dataPages": [
			    {
			      "pageNum": 0,
			      "data": {
			        "1": [1, "PG"],
			        "2": [2, "R"],
			        "3": [3, "NC-17"]
			      }
			    }
			  ]
			}
			""";
		Map<String, DataPackage> packages = new LinkedHashMap<>();
		packages.put("data-public.film.json", new ByteArrayDataPackage("data-public.film.json", tableDataJson));

		PostgresEnumSupport.ensureEnumTypes(dbModel, packages, new ObjectMapper());

		assertThat(dbModel.getEnumTypes(), hasSize(1));
		DbEnumType enumType = dbModel.getEnumTypes().get(0);
		assertThat(enumType.getSchema(), equalTo("public"));
		assertThat(enumType.getName(), equalTo("mpaa_rating"));
		assertThat(enumType.getLabels(), contains("G", "PG", "R", "NC-17"));
	}

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

		PostgresEnumSupport.ensureEnumTypes(dbModel, Map.of(), new ObjectMapper());

		assertThat(dbModel.getEnumTypes(), hasSize(1));
		assertThat(dbModel.getEnumTypes().get(0).getLabels(), hasSize(5));
	}

	private static final class ByteArrayDataPackage implements DataPackage {
		private final String name;
		private final byte[] bytes;

		ByteArrayDataPackage(String name, String content) {
			this.name = name;
			this.bytes = content.getBytes(StandardCharsets.UTF_8);
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public void setName(String name) {
			throw new UnsupportedOperationException();
		}

		@Override
		public String getContentType() {
			return "application/json";
		}

		@Override
		public void setContentType(String contentType) {
			throw new UnsupportedOperationException();
		}

		@Override
		public java.io.File getFile(String destName) {
			throw new UnsupportedOperationException();
		}

		@Override
		public InputStream getInputStream() {
			return new ByteArrayInputStream(bytes);
		}

		@Override
		public void clear() {
		}

		@Override
		public long getLength() {
			return bytes.length;
		}
	}
}
