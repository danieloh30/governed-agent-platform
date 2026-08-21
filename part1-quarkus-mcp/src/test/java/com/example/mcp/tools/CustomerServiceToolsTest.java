package com.example.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.mcp.model.CustomerStatusResponse;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;

/**
 * Demonstrates the testing story that Part 5's evaluation harness builds on:
 * the {@code @Pattern} / {@code @Size} constraints on tool arguments are a real,
 * enforced security boundary — not documentation. Quarkus applies Bean Validation
 * to CDI bean methods automatically (via {@code quarkus-hibernate-validator}), so
 * an invalid argument throws before any business logic runs.
 */
@QuarkusTest
class CustomerServiceToolsTest {

    @Inject
    CustomerServiceTools tools;

    @Test
    void knownCustomerReturnsActiveStatus() {
        CustomerStatusResponse response = tools.getCustomerStatus("CUST-4091");

        assertEquals("CUST-4091", response.customerId());
        assertEquals("ACTIVE", response.status());
        assertEquals("ENTERPRISE_TIER", response.tier());
    }

    @Test
    void unknownButWellFormedCustomerReturnsNotFound() {
        CustomerStatusResponse response = tools.getCustomerStatus("CUST-9999");

        assertEquals("NOT_FOUND", response.status());
    }

    @Test
    void malformedCustomerIdIsRejectedByValidation() {
        // "acme-admin" violates @Pattern("^CUST-[0-9]{4,8}$"): the constraint
        // fires and no lookup happens. This is the boundary Part 5 regression-tests.
        assertThrows(ConstraintViolationException.class,
                () -> tools.getCustomerStatus("acme-admin"));
    }
}
