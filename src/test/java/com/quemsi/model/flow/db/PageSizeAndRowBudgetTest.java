package com.quemsi.model.flow.db;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;

import com.quemsi.model.flow.db.mongodb.DMLServiceMongo;
import com.quemsi.model.flow.db.mysql.DMLServiceMysql;
import com.quemsi.model.flow.db.oracle.DMLServiceOracle;
import com.quemsi.model.flow.db.postgres.DMLServicePostgres;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sqlserver.DMLServiceSqlserver;
import com.quemsi.model.flow.out.RdbmsTarget;

/**
 * Page-size clamp and row-budget invariants used by backup/restore.
 */
public class PageSizeAndRowBudgetTest {

	private static final int MAX_ROWS_PER_PAGE = 5_000;

	@Test
	public void getTablePageSize_clampsToMaxRowsPerPage() {
		DbTable table = new DbTable("dbo", "t");
		assertThat(new DMLServiceSqlserver(null, new ReentrantLock()).getTablePageSize(100, table), equalTo(100));
		assertThat(new DMLServiceSqlserver(null, new ReentrantLock()).getTablePageSize(10_000, table), equalTo(MAX_ROWS_PER_PAGE));
		assertThat(new DMLServicePostgres().getTablePageSize(2_000, table), equalTo(2_000));
		assertThat(new DMLServicePostgres().getTablePageSize(50_000, table), equalTo(MAX_ROWS_PER_PAGE));
		assertThat(new DMLServiceMysql(null).getTablePageSize(null, table), equalTo(1_000));
		assertThat(new DMLServiceOracle(null, new ReentrantLock()).getTablePageSize(0, table), equalTo(1_000));
		assertThat(new DMLServiceMongo().getTablePageSize(8_000, table), equalTo(MAX_ROWS_PER_PAGE));
	}

	@Test
	public void maxRowsPerPage_fitsWithinDefaultRowBudget() {
		assertThat(MAX_ROWS_PER_PAGE, lessThanOrEqualTo(RdbmsTarget.DEFAULT_MAX_IN_FLIGHT_ROWS));
	}

	@Test
	public void rowBudget_semaphoreAllowsMultipleSmallPages() {
		Semaphore inFlightRows = new Semaphore(RdbmsTarget.DEFAULT_MAX_IN_FLIGHT_ROWS);
		int cost = MAX_ROWS_PER_PAGE;
		int slots = RdbmsTarget.DEFAULT_MAX_IN_FLIGHT_ROWS / cost;
		for (int i = 0; i < slots; i++) {
			assertThat(inFlightRows.tryAcquire(cost), equalTo(true));
		}
		assertThat(inFlightRows.tryAcquire(1), equalTo(false));
		inFlightRows.release(cost);
		assertThat(inFlightRows.tryAcquire(cost), equalTo(true));
	}
}
