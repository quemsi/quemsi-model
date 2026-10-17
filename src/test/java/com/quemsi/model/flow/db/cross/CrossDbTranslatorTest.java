package com.quemsi.model.flow.db.cross;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbEnumType;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbSequence;
import com.quemsi.model.flow.db.sql.DbModel.ContraintInfo;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sql.DbView;
import com.quemsi.model.flow.in.TableData.DataPage;

public class CrossDbTranslatorTest {

	@Test
	public void sqlServerTypesMapOntoPostgres() {
		DbModel model = sqlServerModel();
		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.POSTGRES);
		DbTable table = model.findTable("dbo.Widget").orElseThrow();
		DbColumn[] columns = table.orderedColumns();

		assertThat(columns.length, equalTo(4));
		assertThat(columns[0].getColumnType(), equalTo("int4"));
		assertThat(columns[0].isIdentity(), is(true));
		assertThat(columns[1].getColumnType(), equalTo("varchar"));
		assertThat(columns[1].getMaxLength(), equalTo(50));
		assertThat(columns[2].getColumnType(), equalTo("bool"));
		assertThat(columns[2].getColumnDefault(), equalTo("false"));
		assertThat(columns[3].getColumnType(), equalTo("uuid"));
		assertThat(table.findColumn("geo").isPresent(), is(false));
		assertThat(plan.sourceIndexes("dbo.Widget"), equalTo(new int[] { 0, 1, 2, 4 }));
		assertThat(joined(plan), containsString("omitted column dbo.Widget.geo: geography"));
		assertThat(joined(plan), containsString("omitted default on dbo.Widget.name: (getdate())"));
		assertThat(joined(plan), containsString("omitted view dbo.WidgetView"));
		assertThat(model.getViews().size(), equalTo(0));
	}

	@Test
	public void omittedKeyColumnDropsForeignKeyAndNonBtreeIndex() {
		DbModel model = sqlServerModel();
		DbTable parent = model.addTable("Parent", "dbo");
		parent.addColumn(column("id", "int", 1));
		parent.getPkColumnNames().add("id");
		model.getReferenceInfos().add(new ReferenceInfo(
			"fk_geo", "dbo", "Widget", Set.of("geo"), "dbo", "Parent", Set.of("id")));
		IndexInfo gin = new IndexInfo("dbo", "Widget", "ix_geo", false, "SPATIAL");
		gin.getColumns().add("geo");
		model.getIndexes().computeIfAbsent("dbo.Widget", key -> new LinkedHashMap<>()).put(gin.getIndexName(), gin);
		IndexInfo clustered = new IndexInfo("dbo", "Widget", "ix_name", false, "CLUSTERED");
		clustered.getColumns().add("name");
		model.getIndexes().get("dbo.Widget").put(clustered.getIndexName(), clustered);

		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.POSTGRES);

		assertThat(model.getReferenceInfos().size(), equalTo(0));
		assertThat(joined(plan), containsString("dropped foreign key fk_geo"));
		assertThat(joined(plan), containsString("dropped index ix_geo"));
		IndexInfo kept = model.indexesForTable("dbo.Widget").get("ix_name");
		assertThat(kept.getIndexType(), equalTo("btree"));
	}

	@Test
	public void postgresAndOracleTextUsesCaseSensitiveSqlServerCollation() {
		DbModel postgres = new DbModel();
		postgres.setSourceType(DatasourceType.POSTGRES.name());
		DbTable tags = postgres.addTable("tag_value", "public");
		tags.addColumn(DbColumn.builder().name("val").dataType("varchar").columnType("varchar")
			.ordinalPosition(1).maxLength(255).nullable(false).build());

		CrossDbPlan plan = CrossDbTranslator.translate(postgres, DatasourceType.SQLSERVER);
		DbColumn val = postgres.findTable("dbo.tag_value").orElseThrow().column("val");

		assertThat(val.getDataType(), equalTo("nvarchar"));
		assertThat(val.getCollation(), equalTo("Latin1_General_100_BIN2"));
		assertThat(joined(plan), containsString("Latin1_General_100_BIN2"));

		DbModel oracle = new DbModel();
		oracle.setSourceType(DatasourceType.ORACLE.name());
		DbTable names = oracle.addTable("TAG_VALUE", "HR");
		names.addColumn(DbColumn.builder().name("VAL").dataType("VARCHAR2").columnType("VARCHAR2")
			.ordinalPosition(1).maxLength(255).nullable(false).build());
		CrossDbTranslator.translate(oracle, DatasourceType.SQLSERVER);
		assertThat(oracle.findTable("HR.TAG_VALUE").orElseThrow().column("VAL").getCollation(),
			equalTo("Latin1_General_100_BIN2"));

		DbModel mysql = new DbModel();
		mysql.setSourceType(DatasourceType.MYSQL.name());
		DbTable item = mysql.addTable("item", null);
		item.addColumn(DbColumn.builder().name("name").dataType("varchar").columnType("varchar(20)")
			.ordinalPosition(1).maxLength(20).nullable(true).build());
		CrossDbTranslator.translate(mysql, DatasourceType.SQLSERVER);
		assertThat(mysql.findTable("dbo.item").orElseThrow().column("name").getCollation(), equalTo(null));
	}

	@Test
	public void nullableUniqueKeysBecomeFilteredSqlServerIndexes() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.POSTGRES.name());
		DbTable table = model.addTable("flow", "public");
		table.addColumn(DbColumn.builder().name("data_id").dataType("int8").columnType("int8")
			.ordinalPosition(1).nullable(false).build());
		table.addColumn(DbColumn.builder().name("restore_tag_name").dataType("varchar").columnType("varchar")
			.ordinalPosition(2).maxLength(255).nullable(true).build());
		table.addColumn(DbColumn.builder().name("restore_tag_value").dataType("varchar").columnType("varchar")
			.ordinalPosition(3).maxLength(255).nullable(true).build());
		table.addColumn(DbColumn.builder().name("name").dataType("varchar").columnType("varchar")
			.ordinalPosition(4).maxLength(50).nullable(false).build());
		IndexInfo partial = new IndexInfo("public", "flow", "uk_flow_restore_action", true, "btree");
		partial.getColumns().add("data_id");
		partial.getColumns().add("restore_tag_name");
		partial.getColumns().add("restore_tag_value");
		model.getIndexes().computeIfAbsent("public.flow", key -> new LinkedHashMap<>()).put(partial.getIndexName(), partial);
		model.getContraintInfos().add(new ContraintInfo("uk_name", "public", "flow", List.of("name")));
		model.getContraintInfos().add(new ContraintInfo("uk_tag", "public", "flow", List.of("restore_tag_name")));

		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.SQLSERVER);

		IndexInfo restored = model.indexesForTable("dbo.flow").get("uk_flow_restore_action");
		assertThat(restored.getFilter(), equalTo("[restore_tag_name] IS NOT NULL AND [restore_tag_value] IS NOT NULL"));
		assertThat(model.getContraintInfos().stream().map(ContraintInfo::getConstraintName).toList(),
			org.hamcrest.Matchers.contains("uk_name"));
		IndexInfo tag = model.indexesForTable("dbo.flow").get("uk_tag");
		assertThat(tag.isUnique(), is(true));
		assertThat(tag.getFilter(), equalTo("[restore_tag_name] IS NOT NULL"));
		assertThat(joined(plan), containsString("multiple null keys are allowed"));
	}

	@Test
	public void nullableUniqueKeysBecomeOracleFunctionIndexes() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.POSTGRES.name());
		model.setSchemas(Set.of("public"));
		DbTable table = model.addTable("flow", "public");
		table.addColumn(DbColumn.builder().name("data_id").dataType("int8").columnType("int8")
			.ordinalPosition(1).nullable(false).build());
		table.addColumn(DbColumn.builder().name("restore_tag_name").dataType("varchar").columnType("varchar")
			.ordinalPosition(2).maxLength(255).nullable(true).build());
		table.addColumn(DbColumn.builder().name("restore_tag_value").dataType("varchar").columnType("varchar")
			.ordinalPosition(3).maxLength(255).nullable(true).build());
		IndexInfo partial = new IndexInfo("public", "flow", "uk_flow_restore_action", true, "btree");
		partial.getColumns().add("data_id");
		partial.getColumns().add("restore_tag_name");
		partial.getColumns().add("restore_tag_value");
		model.getIndexes().computeIfAbsent("public.flow", key -> new LinkedHashMap<>()).put(partial.getIndexName(), partial);

		CrossDbTranslator.translate(model, DatasourceType.ORACLE, "quemsiapi");

		IndexInfo restored = model.indexesForTable("QUEMSIAPI.flow").get("uk_flow_restore_action");
		assertThat(restored.getFilter(), equalTo("\"restore_tag_name\" IS NOT NULL AND \"restore_tag_value\" IS NOT NULL"));
		assertThat(restored.isUnique(), is(true));
	}

	@Test
	public void postgresOidLargeObjectRestoresAsSqlServerVarbinary() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.POSTGRES.name());
		DbTable table = model.addTable("flow_execution", "public");
		table.addColumn(DbColumn.builder().name("logs").dataType("oid").columnType("oid")
			.ordinalPosition(1).nullable(true).build());

		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.SQLSERVER);
		DbColumn logs = model.findTable("dbo.flow_execution").orElseThrow().column("logs");

		assertThat(logs.getDataType(), equalTo("varbinary"));
		assertThat(logs.getMaxLength(), equalTo(-1));
		assertThat(joined(plan), containsString("oid large objects were written as binary"));
	}

	@Test
	public void postgresNumericWithoutPrecisionMapsToSqlServerDecimal() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.POSTGRES.name());
		DbTable table = model.addTable("wide", "public");
		table.addColumn(DbColumn.builder().name("amount").dataType("numeric").columnType("numeric")
			.ordinalPosition(1).nullable(true).build());

		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.SQLSERVER);
		DbColumn amount = model.findTable("dbo.wide").orElseThrow().column("amount");
		assertThat(joined(plan), containsString("schema public was written as dbo"));

		assertThat(amount.getColumnType(), equalTo("decimal"));
		assertThat(amount.getNumPrecision(), equalTo(38));
		assertThat(amount.getNumScale(), equalTo(0));
	}

	@Test
	public void numericPrecisionAboveTargetIsOmitted() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.POSTGRES.name());
		DbTable table = model.addTable("wide", "public");
		table.addColumn(DbColumn.builder().name("amount").dataType("numeric").columnType("numeric")
			.ordinalPosition(1).numPrecision(40).numScale(2).nullable(true).build());

		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.SQLSERVER);

		assertThat(model.findTable("public.wide").isPresent(), is(false));
		assertThat(joined(plan), containsString("numeric precision 40 exceeds 38"));
	}

	@Test
	public void mysqlUnsignedAndEnum() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.MYSQL.name());
		DbTable table = model.addTable("item", null);
		table.addColumn(DbColumn.builder().name("flag").dataType("tinyint").columnType("tinyint(1)")
			.ordinalPosition(1).nullable(false).build());
		table.addColumn(DbColumn.builder().name("qty").dataType("int").columnType("int unsigned")
			.ordinalPosition(2).nullable(true).build());
		table.addColumn(DbColumn.builder().name("kind").dataType("enum").columnType("enum('a','bb')")
			.ordinalPosition(3).maxLength(2).nullable(true).build());
		table.addColumn(DbColumn.builder().name("huge").dataType("bigint").columnType("bigint unsigned")
			.ordinalPosition(4).nullable(true).build());

		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.POSTGRES);
		DbTable translated = model.findTable("public.item").orElseThrow();

		assertThat(translated.column("flag").getColumnType(), equalTo("bool"));
		assertThat(translated.column("qty").getColumnType(), equalTo("int8"));
		assertThat(translated.column("kind").getColumnType(), equalTo("varchar"));
		assertThat(translated.findColumn("huge").isPresent(), is(false));
		assertThat(joined(plan), containsString("enum constraints were dropped"));
		assertThat(plan.archiveName("public.item"), equalTo("item"));
	}

	@Test
	public void oracleDateIsTimestampAndBooleanNumberRoundTrips() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.ORACLE.name());
		model.setSchemas(Set.of("HR"));
		DbTable table = model.addTable("EMP", "HR");
		table.addColumn(DbColumn.builder().name("HIRED").dataType("DATE").columnType("DATE")
			.ordinalPosition(1).nullable(true).build());
		table.addColumn(DbColumn.builder().name("ACTIVE").dataType("NUMBER").columnType("NUMBER")
			.ordinalPosition(2).numPrecision(1).numScale(0).nullable(false).build());

		CrossDbTranslator.translate(model, DatasourceType.POSTGRES);
		DbTable translated = model.findTable("HR.EMP").orElseThrow();
		assertThat(translated.column("HIRED").getColumnType(), equalTo("timestamp"));
		assertThat(translated.column("ACTIVE").getColumnType(), equalTo("bool"));
	}

	@Test
	public void postgresJsonAndArray() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.POSTGRES.name());
		DbTable table = model.addTable("doc", "public");
		table.addColumn(DbColumn.builder().name("body").dataType("jsonb").columnType("jsonb")
			.ordinalPosition(1).nullable(true).build());
		table.addColumn(DbColumn.builder().name("tags").dataType("_text").columnType("_text")
			.ordinalPosition(2).nullable(true).build());
		model.getEnumTypes().add(DbEnumType.builder().schema("public").name("color").labels(List.of("red", "blue")).build());
		DbTable colored = model.addTable("paint", "public");
		colored.addColumn(DbColumn.builder().name("color").dataType("color").columnType("color")
			.ordinalPosition(1).nullable(true).build());

		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.MYSQL);
		assertThat(model.findTable("doc").orElseThrow().column("body").getColumnType(), equalTo("longtext"));
		assertThat(model.findTable("doc").orElseThrow().findColumn("tags").isPresent(), is(false));
		assertThat(model.findTable("paint").orElseThrow().column("color").getColumnType(), equalTo("varchar(4)"));
		assertThat(joined(plan), containsString("json and jsonb"));
		assertThat(model.getEnumTypes().size(), equalTo(0));
	}

	@Test
	public void mysqlTargetCollidingSchemasFail() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.SQLSERVER.name());
		model.addTable("Item", "dbo").addColumn(column("id", "int", 1));
		model.addTable("Item", "sales").addColumn(column("id", "int", 1));

		BaseRuntimeException ex = assertThrows(BaseRuntimeException.class,
			() -> CrossDbTranslator.translate(model, DatasourceType.MYSQL));
		assertThat(ex.getMessage(), containsString("cross-db-table-name-collision"));
	}

	@Test
	public void readersAndWritersCoverPortableFamilies() {
		CrossDbReport report = new CrossDbReport();
		DbColumn bit = column("active", "bit", 1);
		ColumnMapping bitMapping = new SqlServerTypeReader().read(bit, new DbModel(), report);
		assertThat(bitMapping.column().getType(), is(PortableType.BOOLEAN));

		DbColumn money = column("price", "money", 1);
		assertThat(new SqlServerTypeReader().read(money, new DbModel(), report).column().getPrecision(), equalTo(19));

		DbColumn oracleDate = DbColumn.builder().name("hired").dataType("DATE").ordinalPosition(1).build();
		assertThat(new OracleTypeReader().read(oracleDate, new DbModel(), report).column().getType(), is(PortableType.TIMESTAMP));

		new PostgresTypeWriter().apply(bit, bitMapping.column(), report);
		assertThat(bit.getColumnType(), equalTo("bool"));

		DbColumn uuid = DbColumn.builder().name("id").ordinalPosition(1).build();
		new OracleTypeWriter().apply(uuid, PortableColumn.builder().type(PortableType.UUID).nullable(true).build(), report);
		assertThat(uuid.getDataType(), equalTo("varchar2"));
		assertThat(uuid.getMaxLength(), equalTo(36));
		assertThat(joinedReport(report), containsString("no uuid type"));

		DbColumn text = DbColumn.builder().name("note").ordinalPosition(1).build();
		new SqlServerTypeWriter().apply(text, PortableColumn.builder().type(PortableType.VARCHAR).length(5000).nullable(true).build(), report);
		assertThat(text.getDataType(), equalTo("nvarchar"));
		assertThat(text.getMaxLength(), equalTo(-1));
	}

	@Test
	public void literalDefaultsKeepNumbersAndQuotedStrings() {
		assertThat(LiteralDefaults.parse("((0))").orElseThrow().value(), equalTo("0"));
		assertThat(LiteralDefaults.parse("(N'it''s')").orElseThrow().value(), equalTo("it's"));
		assertThat(LiteralDefaults.parse("(getdate())").isPresent(), is(false));
		assertThat(LiteralDefaults.parse("nextval('public.s'::regclass)").isPresent(), is(false));
	}

	@Test
	public void valueProjectionDropsOmittedSlotsAndConvertsCells() {
		DbTable table = new DbTable(null, "Widget");
		table.addColumn(portableColumn("id", PortableType.INTEGER, 1));
		table.addColumn(portableColumn("active", PortableType.BOOLEAN, 2));
		table.addColumn(portableColumn("token", PortableType.UUID, 3));
		CrossDbPlan plan = new CrossDbPlan(DatasourceType.SQLSERVER, DatasourceType.POSTGRES, new CrossDbReport());
		plan.putSourceIndexes("Widget", new int[] { 0, 2, 4 });
		DataPage page = new DataPage(0, new HashMap<>());
		page.getData().put(1, new Object[] { 1, "skip", 0, "skip2", "AABBCCDD-EEFF-0011-2233-445566778899" });

		DataPage projected = ValueAdapter.project(table, page, plan);
		Object[] row = projected.getData().get(1);
		assertThat(row, arrayWithSize(3));
		assertThat(row[0], equalTo(1));
		assertThat(row[1], equalTo(false));
		assertThat(row[2], equalTo("aabbccdd-eeff-0011-2233-445566778899"));
	}

	@Test
	public void valueAdapterConvertsBinaryEpochAndOracleBoolean() {
		assertThat(ValueAdapter.convert(PortableType.BOOLEAN, 1, DatasourceType.ORACLE), equalTo(1));
		assertThat(ValueAdapter.convert(PortableType.BOOLEAN, "0", DatasourceType.POSTGRES), equalTo(false));
		byte[] bytes = (byte[]) ValueAdapter.convert(PortableType.BINARY, "YWE=", DatasourceType.POSTGRES);
		assertThat(new String(bytes), equalTo("aa"));
		String timestamp = (String) ValueAdapter.convert(PortableType.TIMESTAMP, 1_577_836_800_000L, DatasourceType.SQLSERVER);
		assertThat(timestamp, equalTo("2020-01-01T00:00:00"));
		String zoned = (String) ValueAdapter.convert(PortableType.TIMESTAMPTZ, List.of(2020, 1, 2, 3, 4, 5), DatasourceType.POSTGRES);
		assertThat(zoned, equalTo("2020-01-02T03:04:05Z"));
		assertThat(ValueAdapter.convert(PortableType.DATE, "2020-05-06", DatasourceType.MYSQL), equalTo("2020-05-06"));
		Map<String, String> oid = Map.of("dbType", "oid", "data", "YWE=");
		byte[] logs = (byte[]) ValueAdapter.convert(PortableType.BINARY, oid, DatasourceType.SQLSERVER);
		assertThat(new String(logs), equalTo("aa"));
	}

	@Test
	public void nextvalColumnKeepsSequenceAndDoesNotBecomeIdentity() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.POSTGRES.name());
		model.setSchemas(Set.of("public"));
		DbTable table = model.addTable("flow", "public");
		table.addColumn(DbColumn.builder().name("id").dataType("int8").columnType("int8")
			.ordinalPosition(1).nullable(false).identity(true)
			.columnDefault("nextval('public.flow_id_seq'::regclass)").build());
		table.addColumn(DbColumn.builder().name("real_id").dataType("int8").columnType("int8")
			.ordinalPosition(2).nullable(false).identity(true).build());
		model.getSequences().add(DbSequence.builder().schema("public").name("flow_id_seq")
			.startValue(1L).lastValue(40L).incrementBy(1L).minValue(1L).maxValue(Long.MAX_VALUE).cycle(false).build());

		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.SQLSERVER);
		DbTable restored = model.findTable("dbo.flow").orElseThrow();

		assertThat(restored.column("id").isIdentity(), is(false));
		assertThat(restored.column("id").getColumnDefault(), equalTo(null));
		assertThat(restored.column("real_id").isIdentity(), is(true));
		assertThat(model.getSequences(), org.hamcrest.Matchers.hasSize(1));
		assertThat(model.getSequences().get(0).getSchema(), equalTo("dbo"));
		assertThat(model.getSequences().get(0).getName(), equalTo("flow_id_seq"));
		assertThat(joined(plan), containsString("nextval('public.flow_id_seq'::regclass)"));
		assertThat(joined(plan), org.hamcrest.Matchers.not(containsString("omitted sequence")));
	}

	@Test
	public void postgresPublicSchemaUsesOracleLogin() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.POSTGRES.name());
		model.setSchemas(Set.of("public"));
		DbTable table = model.addTable("flow", "public");
		table.addColumn(DbColumn.builder().name("id").dataType("int8").columnType("int8")
			.ordinalPosition(1).nullable(false).build());
		model.getSequences().add(DbSequence.builder().schema("public").name("flow_id_seq")
			.startValue(1L).incrementBy(1L).build());

		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.ORACLE, "quemsi");

		assertThat(model.findTable("QUEMSI.flow").isPresent(), is(true));
		assertThat(model.getSchemas(), org.hamcrest.Matchers.contains("QUEMSI"));
		assertThat(model.getSequences().get(0).getSchema(), equalTo("QUEMSI"));
		assertThat(joined(plan), containsString("schema public was written as QUEMSI"));
	}

	@Test
	public void sqlServerDboSchemaUsesOracleLogin() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.SQLSERVER.name());
		model.setSchemas(Set.of("dbo"));
		DbTable table = model.addTable("trip", "dbo");
		table.addColumn(DbColumn.builder().name("id").dataType("bigint").columnType("bigint")
			.ordinalPosition(1).nullable(false).build());
		model.getSequences().add(DbSequence.builder().schema("dbo").name("trip_seq")
			.startValue(1L).incrementBy(1L).build());

		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.ORACLE, "nytaxi");

		assertThat(model.findTable("NYTAXI.trip").isPresent(), is(true));
		assertThat(model.getSchemas(), org.hamcrest.Matchers.contains("NYTAXI"));
		assertThat(model.getSequences().get(0).getSchema(), equalTo("NYTAXI"));
		assertThat(joined(plan), containsString("schema dbo was written as NYTAXI"));
	}

	@Test
	public void oracleTargetRequiresLoginWhenSchemaIsPublic() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.POSTGRES.name());
		model.setSchemas(Set.of("public"));
		DbTable table = model.addTable("flow", "public");
		table.addColumn(DbColumn.builder().name("id").dataType("int8").columnType("int8")
			.ordinalPosition(1).nullable(false).build());

		BaseRuntimeException ex = assertThrows(BaseRuntimeException.class,
			() -> CrossDbTranslator.translate(model, DatasourceType.ORACLE));
		assertThat(ex.getMessage(), containsString("oracle-schema-required"));
	}

	@Test
	public void mysqlTargetOmitsSequences() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.POSTGRES.name());
		DbTable table = model.addTable("flow", "public");
		table.addColumn(DbColumn.builder().name("id").dataType("int8").columnType("int8")
			.ordinalPosition(1).nullable(false).build());
		model.getSequences().add(DbSequence.builder().schema("public").name("flow_id_seq")
			.startValue(1L).incrementBy(1L).build());

		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.MYSQL);

		assertThat(model.getSequences(), org.hamcrest.Matchers.empty());
		assertThat(joined(plan), containsString("omitted sequence public.flow_id_seq"));
	}

	@Test
	public void mysqlShortensUniqueIndexThatExceedsKeyLength() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.POSTGRES.name());
		DbTable table = model.addTable("flow", "public");
		table.addColumn(DbColumn.builder().name("data_id").dataType("int8").columnType("int8")
			.ordinalPosition(1).nullable(false).build());
		table.addColumn(DbColumn.builder().name("restore_tag_name").dataType("varchar").columnType("varchar")
			.ordinalPosition(2).maxLength(512).nullable(true).build());
		table.addColumn(DbColumn.builder().name("restore_tag_value").dataType("varchar").columnType("varchar")
			.ordinalPosition(3).maxLength(1024).nullable(true).build());
		IndexInfo partial = new IndexInfo("public", "flow", "uk_flow_restore_action", true, "btree");
		partial.getColumns().add("data_id");
		partial.getColumns().add("restore_tag_name");
		partial.getColumns().add("restore_tag_value");
		model.getIndexes().computeIfAbsent("public.flow", key -> new LinkedHashMap<>()).put(partial.getIndexName(), partial);

		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.MYSQL);
		IndexInfo restored = model.indexesForTable("flow").get("uk_flow_restore_action");

		assertThat(restored.getColumnPrefixLengths().get(0), equalTo(null));
		assertThat(restored.getColumnPrefixLengths().get(1), equalTo(null));
		assertThat(restored.getColumnPrefixLengths().get(2), equalTo(254));
		assertThat(joined(plan), containsString("3072-byte limit"));
	}

	@Test
	public void mysqlTablesAreWrittenInTheOracleSchema() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.MYSQL.name());
		DbTable table = model.addTable("productlines", null);
		table.addColumn(DbColumn.builder().name("productLine").dataType("varchar").columnType("varchar")
			.ordinalPosition(1).maxLength(50).nullable(false).build());

		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.ORACLE, "classicmodels");

		assertThat(model.findTable("CLASSICMODELS.productlines").isPresent(), is(true));
		assertThat(model.getSchemas(), org.hamcrest.Matchers.contains("CLASSICMODELS"));
		assertThat(joined(plan), containsString("written in schema CLASSICMODELS"));
	}

	@Test
	public void mysqlPrimaryAndRepeatedIndexNamesAreUniqueOnPostgres() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.MYSQL.name());
		DbTable lines = model.addTable("productlines", null);
		lines.addColumn(DbColumn.builder().name("productLine").dataType("varchar").columnType("varchar")
			.ordinalPosition(1).maxLength(50).nullable(false).build());
		lines.getPkColumnNames().add("productLine");
		lines.setPkConstraintName("PRIMARY");
		DbTable products = model.addTable("products", null);
		products.addColumn(DbColumn.builder().name("productCode").dataType("varchar").columnType("varchar")
			.ordinalPosition(1).maxLength(15).nullable(false).build());
		products.getPkColumnNames().add("productCode");
		products.setPkConstraintName("PRIMARY");
		IndexInfo linesPk = new IndexInfo(null, "productlines", "PRIMARY", true, "BTREE");
		linesPk.getColumns().add("productLine");
		IndexInfo productsPk = new IndexInfo(null, "products", "PRIMARY", true, "BTREE");
		productsPk.getColumns().add("productCode");
		IndexInfo linesIdx = new IndexInfo(null, "productlines", "idx_name", false, "BTREE");
		linesIdx.getColumns().add("productLine");
		IndexInfo productsIdx = new IndexInfo(null, "products", "idx_name", false, "BTREE");
		productsIdx.getColumns().add("productCode");
		IndexInfo once = new IndexInfo(null, "productlines", "idx_once", false, "BTREE");
		once.getColumns().add("productLine");
		model.getIndexes().computeIfAbsent("productlines", key -> new LinkedHashMap<>()).put("PRIMARY", linesPk);
		model.getIndexes().get("productlines").put("idx_name", linesIdx);
		model.getIndexes().get("productlines").put("idx_once", once);
		model.getIndexes().computeIfAbsent("products", key -> new LinkedHashMap<>()).put("PRIMARY", productsPk);
		model.getIndexes().get("products").put("idx_name", productsIdx);

		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.POSTGRES);

		assertThat(model.findTable("public.productlines").orElseThrow().getPkConstraintName(), equalTo("productlines_pkey"));
		assertThat(model.findTable("public.products").orElseThrow().getPkConstraintName(), equalTo("products_pkey"));
		assertThat(model.indexesForTable("public.productlines").containsKey("PRIMARY"), is(false));
		assertThat(model.indexesForTable("public.productlines").containsKey("productlines_idx_name"), is(true));
		assertThat(model.indexesForTable("public.productlines").containsKey("idx_once"), is(true));
		assertThat(model.indexesForTable("public.products").containsKey("products_idx_name"), is(true));
		assertThat(joined(plan), containsString("{table}_pkey"));
		assertThat(joined(plan), containsString("prefixed with the table name"));
	}

	@Test
	public void identityFlags() {
		assertThat(IdentityFlags.postgres("YES", null), is(true));
		assertThat(IdentityFlags.postgres("NO", "nextval('public.t_id_seq'::regclass)"), is(true));
		assertThat(IdentityFlags.postgres("NO", "0"), is(false));
		assertThat(IdentityFlags.mysql("auto_increment"), is(true));
		assertThat(IdentityFlags.mysql(""), is(false));
		assertThat(IdentityFlags.oracle("YES"), is(true));
		assertThat(IdentityFlags.oracle("NO"), is(false));
	}

	@Test
	public void landingSchemasKeepOracleHrAndRenamePublic() {
		DbModel oracle = new DbModel();
		oracle.setSourceType(DatasourceType.ORACLE.name());
		oracle.setSchemas(Set.of("HR"));
		oracle.crateIfAbsent("EMPLOYEES", "HR");

		assertThat(CrossDbTranslator.crossDbLandingSchemas(oracle, DatasourceType.SQLSERVER, null),
			containsInAnyOrder("HR"));

		DbModel postgres = new DbModel();
		postgres.setSourceType(DatasourceType.POSTGRES.name());
		postgres.setSchemas(Set.of("public"));
		postgres.crateIfAbsent("flow", "public");

		assertThat(CrossDbTranslator.crossDbLandingSchemas(postgres, DatasourceType.SQLSERVER, null),
			containsInAnyOrder("dbo"));
		assertThat(CrossDbTranslator.crossDbLandingSchemas(postgres, DatasourceType.ORACLE, "quemsi"),
			containsInAnyOrder("QUEMSI"));

		DbModel sqlServer = new DbModel();
		sqlServer.setSourceType(DatasourceType.SQLSERVER.name());
		sqlServer.setSchemas(Set.of("dbo"));
		sqlServer.crateIfAbsent("trip", "dbo");
		assertThat(CrossDbTranslator.crossDbLandingSchemas(sqlServer, DatasourceType.ORACLE, "nytaxi"),
			containsInAnyOrder("NYTAXI"));

		DbModel mysql = new DbModel();
		mysql.setSourceType(DatasourceType.MYSQL.name());
		mysql.crateIfAbsent("productlines", null);

		assertThat(CrossDbTranslator.crossDbLandingSchemas(mysql, DatasourceType.ORACLE, "classicmodels"),
			containsInAnyOrder("CLASSICMODELS"));
		assertThat(CrossDbTranslator.crossDbLandingSchemas(mysql, DatasourceType.POSTGRES, null),
			containsInAnyOrder("public"));
		assertThat(CrossDbTranslator.crossDbLandingSchemas(oracle, DatasourceType.ORACLE, null), empty());
	}

	private static DbModel sqlServerModel() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.SQLSERVER.name());
		model.setSchemas(Set.of("dbo"));
		DbTable table = model.addTable("Widget", "dbo");
		table.addColumn(DbColumn.builder().name("id").dataType("int").columnType("int").ordinalPosition(1)
			.nullable(false).identity(true).build());
		table.addColumn(DbColumn.builder().name("name").dataType("nvarchar").columnType("nvarchar").ordinalPosition(2)
			.maxLength(50).nullable(true).columnDefault("(getdate())").build());
		table.addColumn(DbColumn.builder().name("active").dataType("bit").columnType("bit").ordinalPosition(3)
			.nullable(false).columnDefault("((0))").build());
		table.addColumn(DbColumn.builder().name("geo").dataType("geography").columnType("geography").ordinalPosition(4)
			.nullable(true).build());
		table.addColumn(DbColumn.builder().name("token").dataType("uniqueidentifier").columnType("uniqueidentifier")
			.ordinalPosition(5).nullable(true).build());
		table.getPkColumnNames().add("id");
		model.getViews().add(DbView.builder().schema("dbo").name("WidgetView").definition("select 1").build());
		return model;
	}

	private static DbColumn column(String name, String type, int ordinal) {
		return DbColumn.builder().name(name).dataType(type).columnType(type).ordinalPosition(ordinal).nullable(true).build();
	}

	private static DbColumn portableColumn(String name, PortableType type, int ordinal) {
		DbColumn column = DbColumn.builder().name(name).ordinalPosition(ordinal).nullable(true).build();
		column.setPortable(PortableColumn.builder().type(type).nullable(true).build());
		return column;
	}

	private static String joined(CrossDbPlan plan) {
		return String.join("\n", plan.report().warnings());
	}

	private static String joinedReport(CrossDbReport report) {
		return String.join("\n", report.warnings());
	}
}
