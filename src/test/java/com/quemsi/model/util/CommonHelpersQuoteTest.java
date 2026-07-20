package com.quemsi.model.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

public class CommonHelpersQuoteTest {

	@Test
	public void doubleQuoted_escapesEmbeddedQuotes() {
		assertThat(CommonHelpers.doubleQuoted("Genre"), equalTo("\"Genre\""));
		assertThat(CommonHelpers.doubleQuoted("a\"b"), equalTo("\"a\"\"b\""));
	}

	@Test
	public void doubleQuotedQualified_quotesSchemaAndNameSeparately() {
		assertThat(CommonHelpers.doubleQuotedQualified("public", "Genre"), equalTo("\"public\".\"Genre\""));
		assertThat(CommonHelpers.doubleQuotedQualified("public.Genre"), equalTo("\"public\".\"Genre\""));
		assertThat(CommonHelpers.doubleQuotedQualified("Genre"), equalTo("\"Genre\""));
	}
}
