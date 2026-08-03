package com.quemsi.model.flow.subset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.quemsi.commons.util.BaseRuntimeException;

class SubsetPredicateValidatorTest {

    @Test
    void acceptsSimplePredicates() {
        assertDoesNotThrow(() -> SubsetPredicateValidator.validate("t.status = 'FAILED'"));
        assertDoesNotThrow(() -> SubsetPredicateValidator.validate(
            "t.created_at >= '2026-01-01' AND t.status IN ('A','B')"));
        assertDoesNotThrow(() -> SubsetPredicateValidator.validate("t.name LIKE 'x%'"));
        assertDoesNotThrow(() -> SubsetPredicateValidator.validate("t.col IS NULL"));
    }

    @Test
    void rejectsSemicolon() {
        assertThrows(BaseRuntimeException.class,
            () -> SubsetPredicateValidator.validate("t.id = 1; DELETE FROM t"));
    }

    @Test
    void rejectsEmptyIsOk() {
        assertDoesNotThrow(() -> SubsetPredicateValidator.validate(null));
        assertDoesNotThrow(() -> SubsetPredicateValidator.validate(""));
    }
}
