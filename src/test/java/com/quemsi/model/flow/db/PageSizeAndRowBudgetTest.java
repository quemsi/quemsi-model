package com.quemsi.model.flow.db;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.model.flow.db.mongodb.DMLServiceMongo;
import com.quemsi.model.flow.db.mysql.DMLServiceMysql;
import com.quemsi.model.flow.db.oracle.DMLServiceOracle;
import com.quemsi.model.flow.db.postgres.DMLServicePostgres;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sqlserver.DMLServiceSqlserver;
import com.quemsi.model.flow.in.RdbmsBackup;
import com.quemsi.model.flow.out.RdbmsTarget;

/**
 * Page-size and row-budget invariants used by backup/restore.
 */
public class PageSizeAndRowBudgetTest {

	@Test
	public void getTablePageSize_followsBatchSizeWithoutClamp() {
		DbTable table = new DbTable("dbo", "t");
		assertThat(new DMLServiceSqlserver(null, new ReentrantLock()).getTablePageSize(100, table), equalTo(100));
		assertThat(new DMLServiceSqlserver(null, new ReentrantLock()).getTablePageSize(10_000, table), equalTo(10_000));
		assertThat(new DMLServicePostgres().getTablePageSize(50_000, table), equalTo(50_000));
		assertThat(new DMLServiceMysql(null).getTablePageSize(null, table), equalTo(1_000));
		assertThat(new DMLServiceOracle(null, new ReentrantLock()).getTablePageSize(0, table), equalTo(1_000));
		assertThat(new DMLServiceMongo().getTablePageSize(8_000, table), equalTo(8_000));
	}

	@Test
	public void deriveRowBudget_usesArchiveBatchSizeTimesRestoreParallelism() {
		assertThat(RdbmsTarget.deriveRowBudget(10_000, 10), equalTo(100_000));
		assertThat(RdbmsTarget.deriveRowBudget(null, 10), equalTo(RdbmsTarget.DEFAULT_BATCH_SIZE * 10));
		assertThat(RdbmsTarget.deriveRowBudget(0, 5), equalTo(RdbmsTarget.DEFAULT_BATCH_SIZE * 5));
		assertThat(RdbmsTarget.deriveRowBudget(2_000, 1), equalTo(2_000));
	}

	@Test
	public void deriveRowBudget_backup_usesBatchSizeTimesParallelism() {
		assertThat(RdbmsBackup.deriveRowBudget(10_000, 10), equalTo(100_000));
		assertThat(RdbmsBackup.deriveRowBudget(2_000, 1), equalTo(2_000));
		assertThat(RdbmsBackup.deriveRowBudget(500, 0), equalTo(500));
	}

	@Test
	public void totalPages_ceilsRowCountOverPageSize() {
		assertThat(RdbmsBackup.totalPages(0, 10_000), equalTo(0));
		assertThat(RdbmsBackup.totalPages(1, 10_000), equalTo(1));
		assertThat(RdbmsBackup.totalPages(10_000, 10_000), equalTo(1));
		assertThat(RdbmsBackup.totalPages(10_001, 10_000), equalTo(2));
		assertThat(RdbmsBackup.totalPages(25_000, 10_000), equalTo(3));
		assertThat(RdbmsBackup.totalPages(100, 0), equalTo(0));
	}

	@Test
	public void dbModel_roundTripsBatchSizeAndParallelism() throws Exception {
		DbModel model = new DbModel();
		model.setBatchSize(10_000);
		model.setParallelism(10);
		ObjectMapper mapper = new ObjectMapper();
		DbModel read = mapper.readValue(mapper.writeValueAsString(model), DbModel.class);
		assertThat(read.getBatchSize(), equalTo(10_000));
		assertThat(read.getParallelism(), equalTo(10));
	}

	@Test
	public void rowBudget_semaphoreAllowsParallelismFullPages() {
		int batchSize = 10_000;
		int parallelism = 10;
		int budget = RdbmsTarget.deriveRowBudget(batchSize, parallelism);
		Semaphore inFlightRows = new Semaphore(budget);
		for (int i = 0; i < parallelism; i++) {
			assertThat(inFlightRows.tryAcquire(batchSize), equalTo(true));
		}
		assertThat(inFlightRows.tryAcquire(1), equalTo(false));
		inFlightRows.release(batchSize);
		assertThat(inFlightRows.tryAcquire(batchSize), equalTo(true));
	}
}
