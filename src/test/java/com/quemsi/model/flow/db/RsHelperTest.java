package com.quemsi.model.flow.db;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;

public class RsHelperTest {

	@Test
	public void getIntClamped_saturatesMysqlLongtextLength() throws Exception {
		assertThat(helper("4294967295").getIntClamped("CHARACTER_MAXIMUM_LENGTH"), equalTo(Integer.MAX_VALUE));
	}

	@Test
	public void getIntClamped_keepsValuesThatFit() throws Exception {
		assertThat(helper("512").getIntClamped("CHARACTER_MAXIMUM_LENGTH"), equalTo(512));
		assertThat(helper(null).getIntClamped("CHARACTER_MAXIMUM_LENGTH"), nullValue());
	}

	private static RsHelper helper(String numeric) {
		ResultSet rs = (ResultSet) Proxy.newProxyInstance(
			ResultSet.class.getClassLoader(),
			new Class<?>[] { ResultSet.class },
			(proxy, method, args) -> {
				String name = method.getName();
				if ("getBigDecimal".equals(name)) {
					return numeric == null ? null : new BigDecimal(numeric);
				}
				if ("wasNull".equals(name)) {
					return numeric == null;
				}
				if ("equals".equals(name)) {
					return proxy == args[0];
				}
				if ("hashCode".equals(name)) {
					return System.identityHashCode(proxy);
				}
				if ("toString".equals(name)) {
					return "ResultSet";
				}
				throw new UnsupportedOperationException(name);
			});
		return new RsHelper(rs);
	}
}
