package com.flightcontrol.repository;

import com.flightcontrol.domain.FlightStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inserts through plain JDBC so the assertions prove the database itself enforces
 * the constraint, independent of any application-level check.
 */
@DataJpaTest
class FlightSchemaConstraintsTest {

    private static final String INSERT = """
            INSERT INTO flights (flight_number, origin, destination, departure_time, arrival_time, status)
            VALUES (?, 'JFK', 'LHR', DATEADD('DAY', 1, LOCALTIMESTAMP), DATEADD('HOUR', 27, LOCALTIMESTAMP), ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    FlightSchemaConstraintsTest(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void databaseRejectsDuplicateFlightNumber() {
        jdbcTemplate.update(INSERT, "DUP100", "SCHEDULED");

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT, "DUP100", "SCHEDULED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsLowercaseFlightNumber() {
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT, "low100", "SCHEDULED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsMixedCaseFlightNumber() {
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT, "Mix100", "SCHEDULED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsUnknownStatus() {
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT, "BAD100", "BOARDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseAcceptsEveryKnownStatus() {
        for (FlightStatus status : FlightStatus.values()) {
            assertThatCode(() -> jdbcTemplate.update(INSERT, "OK" + status.ordinal(), status.name()))
                    .doesNotThrowAnyException();
        }
    }
}
