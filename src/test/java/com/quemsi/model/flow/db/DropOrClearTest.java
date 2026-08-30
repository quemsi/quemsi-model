package com.quemsi.model.flow.db;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.commons.util.LogMessage;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.Flow;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.TableDataObjectMapper;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbSequence;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sql.diff.DbModelDiff;
import com.quemsi.model.flow.file.BackupArchive;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.quemsi.model.flow.in.TableDataPage;
import com.quemsi.model.flow.subset.SubsetBrowseResult;
import com.quemsi.model.util.CommonConstants;

public class DropOrClearTest {

	@Test
	public void matchingSchema_truncatesAndDoesNotDrop() throws Exception {
		DbModel live = modelWithTable("t1");
		RecordingDdl ddl = new RecordingDdl();
		RecordingDml dml = new RecordingDml();
		FakeDatasource ds = new FakeDatasource(live, ddl, dml);

		DropOrClear step = step(ds);
		step.execute(contextWithArchive(modelWithTable("t1")));

		assertThat(ddl.droppedTables, empty());
		assertThat(dml.truncated, contains("public.t1"));
		assertThat(ddl.disabledConstraints, empty());
	}

	@Test
	public void matchingSchemaWithFk_disablesConstraintsThenTruncates() throws Exception {
		DbModel live = modelWithTableAndFk();
		RecordingDdl ddl = new RecordingDdl();
		RecordingDml dml = new RecordingDml();
		FakeDatasource ds = new FakeDatasource(live, ddl, dml);

		DropOrClear step = step(ds);
		step.execute(contextWithArchive(modelWithTableAndFk()));

		assertThat(ddl.droppedTables, empty());
		assertThat(dml.truncated, contains("public.t1", "public.t2"));
		assertThat(ddl.disabledConstraints, hasSize(1));
		assertThat(ddl.disabledConstraints.iterator().next().getConstraintName(), equalTo("fk_t2_t1"));
	}

	@Test
	public void extraTableInBackup_dropsLiveObjects() throws Exception {
		DbModel live = modelWithTable("t1");
		RecordingDdl ddl = new RecordingDdl();
		RecordingDml dml = new RecordingDml();
		FakeDatasource ds = new FakeDatasource(live, ddl, dml);

		DbModel backup = modelWithTable("t1");
		backup.crateIfAbsent("t2", "public").addColumn(column("id"));

		DropOrClear step = step(ds);
		step.execute(contextWithArchive(backup));

		assertThat(ddl.droppedTables, contains("public.t1"));
		assertThat(dml.truncated, empty());
	}

	@Test
	public void sequenceOnlyDiff_truncates() throws Exception {
		DbModel live = modelWithTable("t1");
		RecordingDdl ddl = new RecordingDdl();
		RecordingDml dml = new RecordingDml();
		FakeDatasource ds = new FakeDatasource(live, ddl, dml);

		DbModel backup = modelWithTable("t1");
		backup.getSequences().add(DbSequence.builder()
			.schema("public").name("t1_seq").incrementBy(1L).cycle(false).build());

		DropOrClear step = step(ds);
		step.execute(contextWithArchive(backup));

		assertThat(ddl.droppedTables, empty());
		assertThat(dml.truncated, contains("public.t1"));
	}

	@Test
	public void fillDetails_includesTypeAndDatasource() {
		RecordingDdl ddl = new RecordingDdl();
		RecordingDml dml = new RecordingDml();
		FakeDatasource ds = new FakeDatasource(modelWithTable("t1"), ddl, dml);
		DropOrClear step = new DropOrClear();
		step.setDatasource(ds);
		step.setAll(true);

		List<java.util.Map<String, Object>> details = new ArrayList<>();
		step.fillDetails(details);

		assertThat(details, hasSize(1));
		assertThat(details.get(0).get("type"), equalTo("DropOrClear"));
		assertThat(details.get(0).get("datasource"), equalTo("test-ds"));
		assertThat(details.get(0).get("all"), equalTo(true));
	}

	private static DropOrClear step(FakeDatasource ds) {
		DropOrClear step = new DropOrClear();
		step.setDatasource(ds);
		step.setAll(true);
		step.setObjectMapper(TableDataObjectMapper.create());
		return step;
	}

	private static FlowContext contextWithArchive(DbModel backup) throws Exception {
		Flow flow = new Flow();
		flow.setId(1L);
		flow.setName("restore");
		FlowContext context = new FlowContext(flow, 1L);
		ObjectMapper mapper = TableDataObjectMapper.create();
		byte[] json = mapper.writeValueAsBytes(backup);
		context.setBackupArchive(new MemoryArchive(json));
		return context;
	}

	private static DbModel modelWithTable(String tableName) {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.POSTGRES.name());
		model.setSchemas(Set.of("public"));
		DbTable table = model.crateIfAbsent(tableName, "public");
		table.addColumn(column("id"));
		model.build();
		return model;
	}

	private static DbModel modelWithTableAndFk() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.POSTGRES.name());
		model.setSchemas(Set.of("public"));
		DbTable t1 = model.crateIfAbsent("t1", "public");
		t1.addColumn(column("id"));
		DbTable t2 = model.crateIfAbsent("t2", "public");
		t2.addColumn(column("id"));
		t2.addColumn(column("t1_id"));
		model.getReferenceInfos().add(ReferenceInfo.builder()
			.constraintName("fk_t2_t1")
			.srcSchema("public").srcTableName("t2").srcColumnName("t1_id")
			.refSchema("public").refTableName("t1").refColumnName("id")
			.build());
		model.build();
		return model;
	}

	private static DbColumn column(String name) {
		return DbColumn.builder()
			.name(name)
			.dataType("int")
			.columnType("int")
			.ordinalPosition(1)
			.nullable(false)
			.identity(false)
			.build();
	}

	private static final class MemoryArchive implements BackupArchive {
		private final byte[] dbModelJson;

		private MemoryArchive(byte[] dbModelJson) {
			this.dbModelJson = dbModelJson;
		}

		@Override
		public InputStream open(String entryName) {
			return new ByteArrayInputStream(dbModelJson);
		}

		@Override
		public boolean exists(String entryName) {
			return CommonConstants.DB_MODEL_FILE_NAME.equals(entryName);
		}

		@Override
		public List<String> list(String prefix) {
			return List.of();
		}

		@Override
		public List<String> listPageEntries(String qualifiedTableName) {
			return List.of();
		}

		@Override
		public void close() {
		}
	}

	private static final class RecordingDdl implements DDLService {
		private final List<String> droppedTables = new ArrayList<>();
		private final Set<ReferenceInfo> disabledConstraints = new LinkedHashSet<>();

		@Override
		public boolean dropTables(String... tableNames) {
			droppedTables.addAll(Arrays.asList(tableNames));
			return true;
		}

		@Override
		public boolean dropSequences(String... sequenceNames) {
			return true;
		}

		@Override
		public boolean dropViews(String... viewNames) {
			return true;
		}

		@Override
		public void disableConstraints(Set<ReferenceInfo> constraints) {
			if (constraints != null) {
				disabledConstraints.addAll(constraints);
			}
		}

		@Override
		public void enableContraints(Set<ReferenceInfo> constraints) {
		}

		@Override
		public void createTables(DbModel dbModel) {
		}

		@Override
		public void createFunctions(DbModel dbModel) {
		}

		@Override
		public void createViews(DbModel dbModel) {
		}

		@Override
		public boolean checkSchema(String schema) {
			return true;
		}

		@Override
		public List<String> ddlFrom(DbModelDiff diff, DbModel dbModel) {
			return List.of();
		}

		@Override
		public void executeSql(String sql) throws SQLException {
		}

		@Override
		public void close() {
		}
	}

	private static final class RecordingDml implements DMLService {
		private final List<String> truncated = new ArrayList<>();

		@Override
		public boolean truncateTables(String... tableNames) {
			if (tableNames != null) {
				truncated.addAll(Arrays.asList(tableNames));
			}
			return true;
		}

		@Override
		public int getTablePageSize(Integer expectedPageSize, DbTable table) {
			return 1000;
		}

		@Override
		public long countRows(DbTable table) {
			return 0;
		}

		@Override
		public TableDataPage getTableDataPage(TableDataPage.Request request) {
			return null;
		}

		@Override
		public int writePageData(DbTable table, DataPage dataPage) {
			return 0;
		}

		@Override
		public boolean clearTables(String... tableNames) {
			return true;
		}

		@Override
		public void updateSequence(String qualifiedSequenceName, Long newValue) {
		}

		@Override
		public Long getMaxColumnValue(String tableName, String columnName) {
			return null;
		}

		@Override
		public SubsetBrowseResult browseRows(DbTable table, String whereFragment, Integer pageSize, Integer page) {
			return null;
		}

		@Override
		public void close() {
		}
	}

	private static final class FakeDatasource implements DataSourceFactory {
		private final DbModel live;
		private final RecordingDdl ddl;
		private final RecordingDml dml;
		private String name = "test-ds";
		private boolean readOnly;

		private FakeDatasource(DbModel live, RecordingDdl ddl, RecordingDml dml) {
			this.live = live;
			this.ddl = ddl;
			this.dml = dml;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public void setName(String name) {
			this.name = name;
		}

		@Override
		public String getDbName() {
			return "testdb";
		}

		@Override
		public void setDbName(String dbName) {
		}

		@Override
		public Set<String> getSchemas() {
			return Set.of("public");
		}

		@Override
		public void setSchemas(Set<String> schemas) {
		}

		@Override
		public String getUrl() {
			return "jdbc:test";
		}

		@Override
		public void setUrl(String url) {
		}

		@Override
		public String getUsername() {
			return "u";
		}

		@Override
		public void setUsername(String username) {
		}

		@Override
		public String getPassword() {
			return "p";
		}

		@Override
		public void setPassword(String password) {
		}

		@Override
		public boolean isReadOnly() {
			return readOnly;
		}

		@Override
		public void setReadOnly(boolean readOnly) {
			this.readOnly = readOnly;
		}

		@Override
		public DataSource getDataSource() {
			return null;
		}

		@Override
		public DbModel getDbModel(Consumer<LogMessage> progress) {
			return live;
		}

		@Override
		public DDLService ddlService() {
			return ddl;
		}

		@Override
		public DMLService dmlService() {
			return dml;
		}

		@Override
		public DatasourceType type() {
			return DatasourceType.POSTGRES;
		}
	}
}
