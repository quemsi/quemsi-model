package com.quemsi.model.flow.subset;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.quemsi.model.flow.db.sql.DbColumn;

class SqlSubsetSupportCoerceTest {

    @Test
    void coercesBigintToLong() {
        DbColumn col = DbColumn.builder().name("id").dataType("bigint").build();
        Object v = SqlSubsetSupport.coercePkValue("42", col);
        assertThat(v, instanceOf(Long.class));
        assertThat(v, equalTo(42L));
    }

    @Test
    void coercesInt8ToLong() {
        DbColumn col = DbColumn.builder().name("id").dataType("int8").build();
        assertThat(SqlSubsetSupport.coercePkValue("99", col), equalTo(99L));
    }

    @Test
    void coercesInteger() {
        DbColumn col = DbColumn.builder().name("id").dataType("integer").build();
        Object v = SqlSubsetSupport.coercePkValue("7", col);
        assertThat(v, equalTo(7));
    }

    @Test
    void coercesUuid() {
        UUID id = UUID.randomUUID();
        DbColumn col = DbColumn.builder().name("id").dataType("uuid").build();
        assertThat(SqlSubsetSupport.coercePkValue(id.toString(), col), equalTo(id));
    }

    @Test
    void coercesNumeric() {
        DbColumn col = DbColumn.builder().name("amount").dataType("numeric").numScale(2).build();
        assertThat(SqlSubsetSupport.coercePkValue("12.50", col), equalTo(new BigDecimal("12.50")));
    }

    @Test
    void canonicalRoundTripBigDecimal() {
        assertThat(SqlSubsetSupport.canonicalPkPart(new BigDecimal("10.00")), equalTo("10"));
        assertThat(SqlSubsetSupport.coercePkValue("10",
            DbColumn.builder().name("n").dataType("numeric").build()), equalTo(new BigDecimal("10")));
    }

    @Test
    void dateRoundTripFromTimestampMidnight() {
        java.sql.Timestamp ts = java.sql.Timestamp.valueOf("2005-01-01 00:00:00.0");
        String canonical = SqlSubsetSupport.canonicalPkPart(ts);
        assertThat(canonical, equalTo("2005-01-01"));
        DbColumn col = DbColumn.builder().name("start_date").dataType("DATE").build();
        Object coerced = SqlSubsetSupport.coercePkValue(canonical, col);
        assertThat(coerced, equalTo(java.sql.Date.valueOf("2005-01-01")));
    }

    @Test
    void dateCoerceAcceptsOracleTimestampString() {
        DbColumn col = DbColumn.builder().name("start_date").dataType("DATE").build();
        Object coerced = SqlSubsetSupport.coercePkValue("2005-01-01 00:00:00.0", col);
        assertThat(coerced, equalTo(java.sql.Date.valueOf("2005-01-01")));
    }
}
