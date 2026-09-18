# Story 2 — Business Rule Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reject invalid flight data on `POST /api/flights` and `PUT /api/flights/{id}` and report every failure in the project's structured error shape, naming the offending field.

**Architecture:** Field-shape rules are Bean Validation annotations on a `FlightRequest` record. `FlightService` normalizes codes to uppercase, aggregates the three data rules (400) using an injected `Clock`, then checks case-insensitive uniqueness (409). One `@RestControllerAdvice` extending `ResponseEntityExceptionHandler` translates every failure into `ApiError`. A schema `CHECK` makes the existing unique constraint effectively case-insensitive at the database level.

**Tech Stack:** Java 21, Spring Boot 3.5.6 (Web, Data JPA, Validation), H2, JUnit 5, Mockito, MockMvc, AssertJ, Maven.

**Spec:** `docs/superpowers/specs/2026-09-18-story-2-validation-design.md` — read it alongside this plan. Project conventions: `CLAUDE.md`.

## Global Constraints

- Work on branch `story-2-validation`. Never commit to `main`.
- Do NOT modify `domain/FlightStatus.java` or add any status-transition logic (Story 3). No GET/DELETE endpoints, no `allowedNextStatuses`/`editable`/`deletable`, no Swagger annotations, no frontend.
- `spring.jpa.hibernate.ddl-auto=none` stays. Schema changes go in `src/main/resources/schema.sql` only.
- Constructor injection only. No `@Autowired` on fields (test constructors may carry `@Autowired`, as existing tests do).
- Entities never cross the controller boundary. The controller contains no `if` on business state.
- No `Optional` as a method parameter.
- Test names read as sentences. Tests build their own data and never rely on `data.sql` rows or execution order.
- Exact error strings (tests assert on them):
  - `departureTime` → `must be in the future`
  - `arrivalTime` → `must be after departure time`
  - `destination` → `must differ from origin`
  - `flightNumber` (duplicate) → `already exists`
  - null field → `is required`
  - unparseable body field → `invalid format`
  - unparseable path parameter → `invalid value`
  - 500 message → `unexpected error`
- `error` names: `ValidationFailed`, `BusinessRuleViolation`, `MalformedRequest`, `FlightNotFound`, `DuplicateFlightNumber`, `InternalError`.
- `fieldErrors` is always present in an error body; `{}` when there are none.
- Commit messages follow the repo style (imperative sentence, e.g. `Add Flight domain model and data layer (Story 1)`), and every commit ends with the trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Base package: `com.flightcontrol`. Source root `src/main/java`, test root `src/test/java`.

## File Structure

| File | Responsibility |
|---|---|
| `src/main/resources/schema.sql` (modify) | add uppercase `CHECK` on `flight_number` |
| `repository/FlightRepository.java` (modify) | two derived `exists…IgnoreCase` queries |
| `pom.xml` (modify) | add `spring-boot-starter-validation` |
| `dto/FlightRequest.java` | request record + field-shape annotations |
| `dto/FlightResponse.java` | response record + `from(Flight)` |
| `dto/ApiError.java` | error body record |
| `config/ClockConfig.java` | `Clock` bean |
| `exception/BusinessRuleViolationException.java` | carries aggregated `fieldErrors` |
| `exception/DuplicateFlightNumberException.java` | 409 duplicate |
| `exception/FlightNotFoundException.java` | 404 |
| `exception/GlobalExceptionHandler.java` | the single `@RestControllerAdvice` |
| `service/FlightService.java` | normalization + all business rules |
| `controller/FlightController.java` | HTTP mapping only |
| `src/main/resources/application.properties` (modify) | never expose stack traces |

Tests: `FlightSchemaConstraintsTest` (extend), `FlightRepositoryTest` (extend), `service/FlightServiceTest`, `controller/FlightControllerTest`, `FlightApiIntegrationTest`.

---

### Task 1: Data layer — case-insensitive uniqueness support

**Files:**
- Modify: `src/main/resources/schema.sql`
- Modify: `src/main/java/com/flightcontrol/repository/FlightRepository.java`
- Test: `src/test/java/com/flightcontrol/repository/FlightSchemaConstraintsTest.java`
- Test: `src/test/java/com/flightcontrol/repository/FlightRepositoryTest.java`

**Interfaces:**
- Consumes: existing `Flight` entity, `FlightRepository extends JpaRepository<Flight, Long>`.
- Produces:
  - `boolean FlightRepository.existsByFlightNumberIgnoreCase(String flightNumber)`
  - `boolean FlightRepository.existsByFlightNumberIgnoreCaseAndIdNot(String flightNumber, Long id)`
  - DB constraint `ck_flights_flight_number_upper`; existing unique constraint name `uk_flights_flight_number` is unchanged.

- [ ] **Step 1: Write the failing schema test**

Add to `FlightSchemaConstraintsTest` (after `databaseRejectsDuplicateFlightNumber`):

```java
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
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q test -Dtest=FlightSchemaConstraintsTest`
Expected: FAIL — both new tests report "Expecting code to raise a throwable" (the insert succeeds today).

- [ ] **Step 3: Add the CHECK constraint**

In `src/main/resources/schema.sql`, add one constraint line after `uk_flights_flight_number`:

```sql
    CONSTRAINT uk_flights_flight_number UNIQUE (flight_number),
    CONSTRAINT ck_flights_flight_number_upper CHECK (flight_number = UPPER(flight_number)),
    CONSTRAINT ck_flights_status CHECK (status IN ('SCHEDULED', 'DELAYED', 'DEPARTED', 'IN_AIR', 'LANDED', 'CANCELLED'))
```

- [ ] **Step 4: Run it to verify it passes**

Run: `mvn -q test -Dtest=FlightSchemaConstraintsTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Write the failing repository tests**

Add to `FlightRepositoryTest` (before the `aFlight` helper):

```java
    @Test
    void findsExistingFlightNumberRegardlessOfCase() {
        flightRepository.saveAndFlush(aFlight("CASE100"));

        assertThat(flightRepository.existsByFlightNumberIgnoreCase("case100")).isTrue();
        assertThat(flightRepository.existsByFlightNumberIgnoreCase("CASE100")).isTrue();
        assertThat(flightRepository.existsByFlightNumberIgnoreCase("CASE999")).isFalse();
    }

    @Test
    void ignoresTheFlightItselfWhenLookingForAnotherWithTheSameNumber() {
        Flight own = flightRepository.saveAndFlush(aFlight("SELF100"));
        Flight other = flightRepository.saveAndFlush(aFlight("SELF200"));

        assertThat(flightRepository.existsByFlightNumberIgnoreCaseAndIdNot("self100", own.getId())).isFalse();
        assertThat(flightRepository.existsByFlightNumberIgnoreCaseAndIdNot("self100", other.getId())).isTrue();
    }
```

- [ ] **Step 6: Run to verify they fail**

Run: `mvn -q test -Dtest=FlightRepositoryTest`
Expected: FAIL — compilation error, `cannot find symbol: method existsByFlightNumberIgnoreCase`.

- [ ] **Step 7: Add the derived queries**

Replace the body of `FlightRepository`:

```java
public interface FlightRepository extends JpaRepository<Flight, Long> {

    boolean existsByFlightNumberIgnoreCase(String flightNumber);

    boolean existsByFlightNumberIgnoreCaseAndIdNot(String flightNumber, Long id);
}
```

- [ ] **Step 8: Run the whole suite**

Run: `mvn -q test`
Expected: PASS — all existing tests plus 4 new ones. (`SeedDataTest` still passes: every seed flight number is already uppercase.)

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/schema.sql src/main/java/com/flightcontrol/repository/FlightRepository.java src/test/java/com/flightcontrol/repository
git commit -m "Enforce uppercase flight numbers in schema and add case-insensitive lookups (Story 2)" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: `FlightService.create` — normalization, data rules, uniqueness

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/flightcontrol/dto/FlightRequest.java`
- Create: `src/main/java/com/flightcontrol/dto/FlightResponse.java`
- Create: `src/main/java/com/flightcontrol/config/ClockConfig.java`
- Create: `src/main/java/com/flightcontrol/exception/BusinessRuleViolationException.java`
- Create: `src/main/java/com/flightcontrol/exception/DuplicateFlightNumberException.java`
- Create: `src/main/java/com/flightcontrol/service/FlightService.java`
- Test: `src/test/java/com/flightcontrol/service/FlightServiceTest.java`

**Interfaces:**
- Consumes: `FlightRepository.existsByFlightNumberIgnoreCase(String)`, `JpaRepository.saveAndFlush(Flight)`, `Flight(String flightNumber, String origin, String destination, LocalDateTime departureTime, LocalDateTime arrivalTime)`.
- Produces:
  - `record FlightRequest(String flightNumber, String origin, String destination, LocalDateTime departureTime, LocalDateTime arrivalTime)`
  - `record FlightResponse(Long id, String flightNumber, String origin, String destination, LocalDateTime departureTime, LocalDateTime arrivalTime, FlightStatus status, LocalDateTime createdAt, LocalDateTime updatedAt)` with `static FlightResponse from(Flight flight)`
  - `BusinessRuleViolationException(Map<String, String> fieldErrors)` with `Map<String, String> getFieldErrors()`; message `Flight data violates business rules`
  - `DuplicateFlightNumberException(String flightNumber)`; message `Flight number <N> already exists`
  - `FlightService(FlightRepository flightRepository, Clock clock)` with `FlightResponse create(FlightRequest request)`
  - `Clock` bean from `ClockConfig`

- [ ] **Step 1: Add the validation starter to `pom.xml`**

After the `spring-boot-starter-data-jpa` dependency:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
```

- [ ] **Step 2: Write the failing service test**

Create `src/test/java/com/flightcontrol/service/FlightServiceTest.java`:

```java
package com.flightcontrol.service;

import com.flightcontrol.domain.Flight;
import com.flightcontrol.domain.FlightStatus;
import com.flightcontrol.dto.FlightRequest;
import com.flightcontrol.dto.FlightResponse;
import com.flightcontrol.exception.BusinessRuleViolationException;
import com.flightcontrol.exception.DuplicateFlightNumberException;
import com.flightcontrol.repository.FlightRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlightServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 1, 10, 0);
    private static final LocalDateTime DEPARTURE = NOW.plusDays(1);
    private static final LocalDateTime ARRIVAL = DEPARTURE.plusHours(8);

    @Mock
    private FlightRepository flightRepository;

    private FlightService flightService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        flightService = new FlightService(flightRepository, fixedClock);
    }

    @Test
    void createsFlightAsScheduled() {
        savingReturnsTheFlight();

        FlightResponse response = flightService.create(validRequest());

        assertThat(response.status()).isEqualTo(FlightStatus.SCHEDULED);
        assertThat(response.flightNumber()).isEqualTo("BA117");
        assertThat(response.departureTime()).isEqualTo(DEPARTURE);
        assertThat(response.arrivalTime()).isEqualTo(ARRIVAL);
    }

    @Test
    void storesFlightNumberAndAirportCodesInUpperCase() {
        savingReturnsTheFlight();

        flightService.create(new FlightRequest("ba117", "lhr", "jfk", DEPARTURE, ARRIVAL));

        verify(flightRepository).existsByFlightNumberIgnoreCase("BA117");
        ArgumentCaptor<Flight> saved = ArgumentCaptor.forClass(Flight.class);
        verify(flightRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getFlightNumber()).isEqualTo("BA117");
        assertThat(saved.getValue().getOrigin()).isEqualTo("LHR");
        assertThat(saved.getValue().getDestination()).isEqualTo("JFK");
    }

    @Test
    void rejectsDepartureTimeInThePast() {
        LocalDateTime past = NOW.minusMinutes(1);
        FlightRequest request = new FlightRequest("BA117", "LHR", "JFK", past, past.plusHours(8));

        assertThatThrownBy(() -> flightService.create(request))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsOnly(entry("departureTime", "must be in the future")));
    }

    @Test
    void rejectsDepartureTimeEqualToNow() {
        FlightRequest request = new FlightRequest("BA117", "LHR", "JFK", NOW, NOW.plusHours(8));

        assertThatThrownBy(() -> flightService.create(request))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsOnly(entry("departureTime", "must be in the future")));
    }

    @Test
    void rejectsArrivalTimeBeforeDeparture() {
        FlightRequest request = new FlightRequest("BA117", "LHR", "JFK", DEPARTURE, DEPARTURE.minusMinutes(1));

        assertThatThrownBy(() -> flightService.create(request))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsOnly(entry("arrivalTime", "must be after departure time")));
    }

    @Test
    void rejectsArrivalTimeEqualToDeparture() {
        FlightRequest request = new FlightRequest("BA117", "LHR", "JFK", DEPARTURE, DEPARTURE);

        assertThatThrownBy(() -> flightService.create(request))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsOnly(entry("arrivalTime", "must be after departure time")));
    }

    @Test
    void rejectsSameOriginAndDestination() {
        FlightRequest request = new FlightRequest("BA117", "LHR", "LHR", DEPARTURE, ARRIVAL);

        assertThatThrownBy(() -> flightService.create(request))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsOnly(entry("destination", "must differ from origin")));
    }

    @Test
    void rejectsSameOriginAndDestinationDifferingOnlyByCase() {
        FlightRequest request = new FlightRequest("BA117", "lhr", "LHR", DEPARTURE, ARRIVAL);

        assertThatThrownBy(() -> flightService.create(request))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsOnly(entry("destination", "must differ from origin")));
    }

    @Test
    void reportsEveryViolatedRuleInOneException() {
        LocalDateTime past = NOW.minusHours(1);
        FlightRequest request = new FlightRequest("BA117", "LHR", "LHR", past, past.minusHours(1));

        assertThatThrownBy(() -> flightService.create(request))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex ->
                        assertThat(ex.getFieldErrors()).containsOnly(
                                entry("departureTime", "must be in the future"),
                                entry("arrivalTime", "must be after departure time"),
                                entry("destination", "must differ from origin")));
    }

    @Test
    void rejectsDuplicateFlightNumberIgnoringCase() {
        when(flightRepository.existsByFlightNumberIgnoreCase("BA117")).thenReturn(true);
        FlightRequest request = new FlightRequest("ba117", "LHR", "JFK", DEPARTURE, ARRIVAL);

        assertThatThrownBy(() -> flightService.create(request))
                .isInstanceOf(DuplicateFlightNumberException.class)
                .hasMessage("Flight number BA117 already exists");
        verify(flightRepository, never()).saveAndFlush(any());
    }

    @Test
    void skipsUniquenessCheckWhenDataRulesFail() {
        FlightRequest request = new FlightRequest("BA117", "LHR", "LHR", DEPARTURE, ARRIVAL);

        assertThatThrownBy(() -> flightService.create(request))
                .isInstanceOf(BusinessRuleViolationException.class);
        verifyNoInteractions(flightRepository);
    }

    private void savingReturnsTheFlight() {
        when(flightRepository.saveAndFlush(any(Flight.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static FlightRequest validRequest() {
        return new FlightRequest("BA117", "LHR", "JFK", DEPARTURE, ARRIVAL);
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `mvn -q test -Dtest=FlightServiceTest`
Expected: FAIL — compilation errors, `package com.flightcontrol.dto does not exist`, `cannot find symbol: class FlightService`.

- [ ] **Step 4: Create `FlightRequest`**

```java
package com.flightcontrol.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDateTime;

/**
 * Field-shape rules only. Cross-field and uniqueness rules live in FlightService.
 * NotNull + Pattern gives exactly one message per field: Pattern passes on null and fails on "".
 */
public record FlightRequest(

        @NotNull(message = "is required")
        @Pattern(regexp = "^[A-Za-z0-9]{2}\\d{1,4}[A-Za-z]?$",
                message = "must be a 2-character airline code followed by 1-4 digits and an optional letter, e.g. BA117")
        String flightNumber,

        @NotNull(message = "is required")
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter airport code, e.g. LHR")
        String origin,

        @NotNull(message = "is required")
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter airport code, e.g. LHR")
        String destination,

        @NotNull(message = "is required")
        LocalDateTime departureTime,

        @NotNull(message = "is required")
        LocalDateTime arrivalTime
) {
}
```

- [ ] **Step 5: Create `FlightResponse`**

```java
package com.flightcontrol.dto;

import com.flightcontrol.domain.Flight;
import com.flightcontrol.domain.FlightStatus;

import java.time.LocalDateTime;

public record FlightResponse(
        Long id,
        String flightNumber,
        String origin,
        String destination,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        FlightStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static FlightResponse from(Flight flight) {
        return new FlightResponse(
                flight.getId(),
                flight.getFlightNumber(),
                flight.getOrigin(),
                flight.getDestination(),
                flight.getDepartureTime(),
                flight.getArrivalTime(),
                flight.getStatus(),
                flight.getCreatedAt(),
                flight.getUpdatedAt());
    }
}
```

- [ ] **Step 6: Create the two exceptions**

`src/main/java/com/flightcontrol/exception/BusinessRuleViolationException.java`:

```java
package com.flightcontrol.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class BusinessRuleViolationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public BusinessRuleViolationException(Map<String, String> fieldErrors) {
        super("Flight data violates business rules");
        this.fieldErrors = Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
```

`src/main/java/com/flightcontrol/exception/DuplicateFlightNumberException.java`:

```java
package com.flightcontrol.exception;

public class DuplicateFlightNumberException extends RuntimeException {

    public DuplicateFlightNumberException(String flightNumber) {
        super("Flight number " + flightNumber + " already exists");
    }
}
```

- [ ] **Step 7: Create `ClockConfig`**

```java
package com.flightcontrol.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
```

- [ ] **Step 8: Create `FlightService` with `create`**

```java
package com.flightcontrol.service;

import com.flightcontrol.domain.Flight;
import com.flightcontrol.dto.FlightRequest;
import com.flightcontrol.dto.FlightResponse;
import com.flightcontrol.exception.BusinessRuleViolationException;
import com.flightcontrol.exception.DuplicateFlightNumberException;
import com.flightcontrol.repository.FlightRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class FlightService {

    private final FlightRepository flightRepository;
    private final Clock clock;

    public FlightService(FlightRepository flightRepository, Clock clock) {
        this.flightRepository = flightRepository;
        this.clock = clock;
    }

    @Transactional
    public FlightResponse create(FlightRequest request) {
        FlightRequest details = normalize(request);
        validateDataRules(details);
        if (flightRepository.existsByFlightNumberIgnoreCase(details.flightNumber())) {
            throw new DuplicateFlightNumberException(details.flightNumber());
        }
        Flight flight = new Flight(details.flightNumber(), details.origin(), details.destination(),
                details.departureTime(), details.arrivalTime());
        // Flush so a unique-constraint race surfaces here, translated, not at commit.
        return FlightResponse.from(flightRepository.saveAndFlush(flight));
    }

    private static FlightRequest normalize(FlightRequest request) {
        return new FlightRequest(
                request.flightNumber().toUpperCase(Locale.ROOT),
                request.origin().toUpperCase(Locale.ROOT),
                request.destination().toUpperCase(Locale.ROOT),
                request.departureTime(),
                request.arrivalTime());
    }

    private void validateDataRules(FlightRequest details) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        if (!details.departureTime().isAfter(LocalDateTime.now(clock))) {
            fieldErrors.put("departureTime", "must be in the future");
        }
        if (!details.arrivalTime().isAfter(details.departureTime())) {
            fieldErrors.put("arrivalTime", "must be after departure time");
        }
        if (details.origin().equals(details.destination())) {
            fieldErrors.put("destination", "must differ from origin");
        }
        if (!fieldErrors.isEmpty()) {
            throw new BusinessRuleViolationException(fieldErrors);
        }
    }
}
```

- [ ] **Step 9: Run the service tests**

Run: `mvn -q test -Dtest=FlightServiceTest`
Expected: PASS (11 tests).

- [ ] **Step 10: Run the whole suite**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add pom.xml src/main/java/com/flightcontrol/dto src/main/java/com/flightcontrol/config src/main/java/com/flightcontrol/exception src/main/java/com/flightcontrol/service src/test/java/com/flightcontrol/service
git commit -m "Add FlightService.create with business rule validation (Story 2)" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: `FlightService.update` — re-validation and self-excluding uniqueness

**Files:**
- Create: `src/main/java/com/flightcontrol/exception/FlightNotFoundException.java`
- Modify: `src/main/java/com/flightcontrol/service/FlightService.java`
- Test: `src/test/java/com/flightcontrol/service/FlightServiceTest.java`

**Interfaces:**
- Consumes: `FlightService` from Task 2 (private `normalize(FlightRequest)` and `validateDataRules(FlightRequest)`), `FlightRepository.existsByFlightNumberIgnoreCaseAndIdNot(String, Long)`, `JpaRepository.findById(Long)`.
- Produces:
  - `FlightNotFoundException(Long id)`; message `Flight <id> does not exist`
  - `FlightResponse FlightService.update(Long id, FlightRequest request)`

- [ ] **Step 1: Write the failing tests**

Add these imports to `FlightServiceTest`:

```java
import com.flightcontrol.exception.FlightNotFoundException;
import java.util.Optional;
```

Add these tests before the `savingReturnsTheFlight` helper:

```java
    @Test
    void updatesEveryDetailFieldInUpperCase() {
        Flight existing = new Flight("BA117", "LHR", "JFK", DEPARTURE, ARRIVAL);
        when(flightRepository.findById(7L)).thenReturn(Optional.of(existing));
        savingReturnsTheFlight();
        LocalDateTime newDeparture = DEPARTURE.plusDays(2);

        FlightResponse response = flightService.update(7L,
                new FlightRequest("ba200", "cdg", "sfo", newDeparture, newDeparture.plusHours(11)));

        assertThat(response.flightNumber()).isEqualTo("BA200");
        assertThat(response.origin()).isEqualTo("CDG");
        assertThat(response.destination()).isEqualTo("SFO");
        assertThat(response.departureTime()).isEqualTo(newDeparture);
        assertThat(response.arrivalTime()).isEqualTo(newDeparture.plusHours(11));
        verify(flightRepository).saveAndFlush(existing);
    }

    @Test
    void rejectsUpdateOfUnknownFlight() {
        when(flightRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> flightService.update(99L, validRequest()))
                .isInstanceOf(FlightNotFoundException.class)
                .hasMessage("Flight 99 does not exist");
    }

    @Test
    void allowsUpdateKeepingOwnFlightNumber() {
        Flight existing = new Flight("BA117", "LHR", "JFK", DEPARTURE, ARRIVAL);
        when(flightRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(flightRepository.existsByFlightNumberIgnoreCaseAndIdNot("BA117", 7L)).thenReturn(false);
        savingReturnsTheFlight();

        FlightResponse response = flightService.update(7L,
                new FlightRequest("ba117", "LHR", "BOS", DEPARTURE, ARRIVAL));

        assertThat(response.flightNumber()).isEqualTo("BA117");
        assertThat(response.destination()).isEqualTo("BOS");
        verify(flightRepository, never()).existsByFlightNumberIgnoreCase(any());
    }

    @Test
    void rejectsUpdateToAnotherFlightsNumber() {
        Flight existing = new Flight("BA117", "LHR", "JFK", DEPARTURE, ARRIVAL);
        when(flightRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(flightRepository.existsByFlightNumberIgnoreCaseAndIdNot("LH400", 7L)).thenReturn(true);

        assertThatThrownBy(() -> flightService.update(7L,
                new FlightRequest("lh400", "LHR", "JFK", DEPARTURE, ARRIVAL)))
                .isInstanceOf(DuplicateFlightNumberException.class)
                .hasMessage("Flight number LH400 already exists");
        verify(flightRepository, never()).saveAndFlush(any());
        assertThat(existing.getFlightNumber()).isEqualTo("BA117");
    }

    @Test
    void rejectsUpdateWithDepartureTimeInThePast() {
        Flight existing = new Flight("BA117", "LHR", "JFK", DEPARTURE, ARRIVAL);
        when(flightRepository.findById(7L)).thenReturn(Optional.of(existing));
        LocalDateTime past = NOW.minusMinutes(1);

        assertThatThrownBy(() -> flightService.update(7L,
                new FlightRequest("BA117", "LHR", "JFK", past, past.plusHours(8))))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsOnly(entry("departureTime", "must be in the future")));
        verify(flightRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsUpdateWithArrivalTimeNotAfterDeparture() {
        Flight existing = new Flight("BA117", "LHR", "JFK", DEPARTURE, ARRIVAL);
        when(flightRepository.findById(7L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> flightService.update(7L,
                new FlightRequest("BA117", "LHR", "JFK", DEPARTURE, DEPARTURE)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsOnly(entry("arrivalTime", "must be after departure time")));
        verify(flightRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsUpdateWithSameOriginAndDestination() {
        Flight existing = new Flight("BA117", "LHR", "JFK", DEPARTURE, ARRIVAL);
        when(flightRepository.findById(7L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> flightService.update(7L,
                new FlightRequest("BA117", "JFK", "jfk", DEPARTURE, ARRIVAL)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsOnly(entry("destination", "must differ from origin")));
        verify(flightRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateLeavesStatusUnchanged() {
        Flight existing = new Flight("BA117", "LHR", "JFK", DEPARTURE, ARRIVAL);
        existing.setStatus(FlightStatus.DELAYED);
        when(flightRepository.findById(7L)).thenReturn(Optional.of(existing));
        savingReturnsTheFlight();

        FlightResponse response = flightService.update(7L, validRequest());

        assertThat(response.status()).isEqualTo(FlightStatus.DELAYED);
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `mvn -q test -Dtest=FlightServiceTest`
Expected: FAIL — compilation errors, `cannot find symbol: class FlightNotFoundException` and `method update`.

- [ ] **Step 3: Create `FlightNotFoundException`**

```java
package com.flightcontrol.exception;

public class FlightNotFoundException extends RuntimeException {

    public FlightNotFoundException(Long id) {
        super("Flight " + id + " does not exist");
    }
}
```

- [ ] **Step 4: Add `update` to `FlightService`**

Add the import `com.flightcontrol.exception.FlightNotFoundException` and this method directly after `create`:

```java
    @Transactional
    public FlightResponse update(Long id, FlightRequest request) {
        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));
        FlightRequest details = normalize(request);
        validateDataRules(details);
        if (flightRepository.existsByFlightNumberIgnoreCaseAndIdNot(details.flightNumber(), id)) {
            throw new DuplicateFlightNumberException(details.flightNumber());
        }
        flight.setFlightNumber(details.flightNumber());
        flight.setOrigin(details.origin());
        flight.setDestination(details.destination());
        flight.setDepartureTime(details.departureTime());
        flight.setArrivalTime(details.arrivalTime());
        // Flush so updatedAt is refreshed before mapping and constraint races surface here.
        return FlightResponse.from(flightRepository.saveAndFlush(flight));
    }
```

Do not read or write `flight.getStatus()` here: status guardrails are Story 4.

- [ ] **Step 5: Run the service tests**

Run: `mvn -q test -Dtest=FlightServiceTest`
Expected: PASS (19 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/flightcontrol/exception/FlightNotFoundException.java src/main/java/com/flightcontrol/service/FlightService.java src/test/java/com/flightcontrol/service/FlightServiceTest.java
git commit -m "Add FlightService.update re-validating every business rule (Story 2)" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: HTTP layer — controller, `ApiError`, advice for validation and domain exceptions

**Files:**
- Create: `src/main/java/com/flightcontrol/dto/ApiError.java`
- Create: `src/main/java/com/flightcontrol/exception/GlobalExceptionHandler.java`
- Create: `src/main/java/com/flightcontrol/controller/FlightController.java`
- Test: `src/test/java/com/flightcontrol/controller/FlightControllerTest.java`

**Interfaces:**
- Consumes: `FlightService.create(FlightRequest)`, `FlightService.update(Long, FlightRequest)`, `FlightRequest`, `FlightResponse`, `BusinessRuleViolationException.getFieldErrors()`, `DuplicateFlightNumberException`, `FlightNotFoundException`, `Clock` bean.
- Produces:
  - `record ApiError(Instant timestamp, int status, String error, String message, Map<String, String> fieldErrors)`
  - `GlobalExceptionHandler(Clock clock) extends ResponseEntityExceptionHandler` with private helper `ResponseEntity<Object> respond(HttpStatus status, String error, String message, Map<String, String> fieldErrors, HttpHeaders headers)` — Tasks 5 and 6 add methods that call it.
  - `POST /api/flights` → 201 + `Location: /api/flights/{id}`; `PUT /api/flights/{id}` → 200.

Notes for the implementer:
- `@WebMvcTest` does not load `@Configuration` classes, so `ClockConfig` is absent in the slice. The test supplies its own fixed `Clock` through a nested `@TestConfiguration`; that also makes `timestamp` assertable.
- `ResponseEntityExceptionHandler` already declares `@ExceptionHandler` for `MethodArgumentNotValidException`. Declaring a second one causes an "Ambiguous @ExceptionHandler" startup failure. Override the protected `handle…` method instead.

- [ ] **Step 1: Write the failing controller test**

Create `src/test/java/com/flightcontrol/controller/FlightControllerTest.java`:

```java
package com.flightcontrol.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flightcontrol.domain.FlightStatus;
import com.flightcontrol.dto.FlightRequest;
import com.flightcontrol.dto.FlightResponse;
import com.flightcontrol.exception.BusinessRuleViolationException;
import com.flightcontrol.exception.DuplicateFlightNumberException;
import com.flightcontrol.exception.FlightNotFoundException;
import com.flightcontrol.service.FlightService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FlightController.class)
class FlightControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDateTime DEPARTURE = LocalDateTime.of(2030, 6, 1, 10, 15, 30);
    private static final LocalDateTime ARRIVAL = LocalDateTime.of(2030, 6, 1, 18, 45, 30);
    private static final FlightResponse SAVED = new FlightResponse(42L, "BA117", "LHR", "JFK",
            DEPARTURE, ARRIVAL, FlightStatus.SCHEDULED, DEPARTURE.minusDays(30), DEPARTURE.minusDays(30));

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2030-01-01T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @MockitoBean
    private FlightService flightService;

    private final MockMvc mockMvc;

    @Autowired
    FlightControllerTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void createsFlightAndReturnsItsLocation() throws Exception {
        when(flightService.create(any(FlightRequest.class))).thenReturn(SAVED);

        mockMvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON).content(json(validBody())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/flights/42"))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.flightNumber").value("BA117"))
                .andExpect(jsonPath("$.origin").value("LHR"))
                .andExpect(jsonPath("$.destination").value("JFK"))
                .andExpect(jsonPath("$.departureTime").value("2030-06-01T10:15:30"))
                .andExpect(jsonPath("$.arrivalTime").value("2030-06-01T18:45:30"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"));
    }

    @Test
    void acceptsLowercaseCodesAndLeavesNormalizationToTheService() throws Exception {
        when(flightService.create(any(FlightRequest.class))).thenReturn(SAVED);
        Map<String, Object> body = validBody();
        body.put("flightNumber", "ba117");
        body.put("origin", "lhr");
        body.put("destination", "jfk");

        mockMvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated());
    }

    @Test
    void updatesFlight() throws Exception {
        when(flightService.update(eq(42L), any(FlightRequest.class))).thenReturn(SAVED);

        mockMvc.perform(put("/api/flights/42").contentType(MediaType.APPLICATION_JSON).content(json(validBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.flightNumber").value("BA117"));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("malformedFields")
    void rejectsMissingOrMalformedFieldOnCreate(String field, Object value) throws Exception {
        Map<String, Object> body = validBody();
        body.put(field, value);

        mockMvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("ValidationFailed"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.timestamp").value("2030-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.fieldErrors.*", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors." + field).isNotEmpty());
        verifyNoInteractions(flightService);
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("malformedFields")
    void rejectsMissingOrMalformedFieldOnUpdate(String field, Object value) throws Exception {
        Map<String, Object> body = validBody();
        body.put(field, value);

        mockMvc.perform(put("/api/flights/42").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ValidationFailed"))
                .andExpect(jsonPath("$.fieldErrors.*", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors." + field).isNotEmpty());
        verifyNoInteractions(flightService);
    }

    static Stream<Arguments> malformedFields() {
        return Stream.of(
                Arguments.of("flightNumber", null),
                Arguments.of("flightNumber", ""),
                Arguments.of("flightNumber", "B"),
                Arguments.of("flightNumber", "BA"),
                Arguments.of("flightNumber", "BA12345"),
                Arguments.of("flightNumber", "BA 117"),
                Arguments.of("flightNumber", "BA-117"),
                Arguments.of("flightNumber", "BA117XY"),
                Arguments.of("origin", null),
                Arguments.of("origin", ""),
                Arguments.of("origin", "LH"),
                Arguments.of("origin", "LHRX"),
                Arguments.of("origin", "L1R"),
                Arguments.of("destination", null),
                Arguments.of("destination", "JF"),
                Arguments.of("destination", "JFKX"),
                Arguments.of("destination", "J K"),
                Arguments.of("departureTime", null),
                Arguments.of("arrivalTime", null));
    }

    @Test
    void reportsEveryMalformedFieldAtOnce() throws Exception {
        Map<String, Object> body = validBody();
        body.put("flightNumber", "!!");
        body.put("origin", null);
        body.put("arrivalTime", null);

        mockMvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.*", hasSize(3)))
                .andExpect(jsonPath("$.fieldErrors.origin").value("is required"))
                .andExpect(jsonPath("$.fieldErrors.arrivalTime").value("is required"))
                .andExpect(jsonPath("$.fieldErrors.flightNumber").isNotEmpty());
    }

    @Test
    void returnsBadRequestWithFieldErrorsWhenBusinessRulesAreViolated() throws Exception {
        Map<String, String> violations = new LinkedHashMap<>();
        violations.put("departureTime", "must be in the future");
        violations.put("destination", "must differ from origin");
        when(flightService.create(any(FlightRequest.class)))
                .thenThrow(new BusinessRuleViolationException(violations));

        mockMvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON).content(json(validBody())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("BusinessRuleViolation"))
                .andExpect(jsonPath("$.message").value("Flight data violates business rules"))
                .andExpect(jsonPath("$.timestamp").value("2030-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.fieldErrors.*", hasSize(2)))
                .andExpect(jsonPath("$.fieldErrors.departureTime").value("must be in the future"))
                .andExpect(jsonPath("$.fieldErrors.destination").value("must differ from origin"));
    }

    @Test
    void returnsConflictWhenFlightNumberAlreadyExists() throws Exception {
        when(flightService.create(any(FlightRequest.class)))
                .thenThrow(new DuplicateFlightNumberException("BA117"));

        mockMvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON).content(json(validBody())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("DuplicateFlightNumber"))
                .andExpect(jsonPath("$.message").value("Flight number BA117 already exists"))
                .andExpect(jsonPath("$.fieldErrors.*", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors.flightNumber").value("already exists"));
    }

    @Test
    void returnsConflictWhenUpdatingToAnotherFlightsNumber() throws Exception {
        when(flightService.update(eq(42L), any(FlightRequest.class)))
                .thenThrow(new DuplicateFlightNumberException("LH400"));

        mockMvc.perform(put("/api/flights/42").contentType(MediaType.APPLICATION_JSON).content(json(validBody())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DuplicateFlightNumber"))
                .andExpect(jsonPath("$.fieldErrors.flightNumber").value("already exists"));
    }

    @Test
    void returnsNotFoundWhenUpdatingUnknownFlight() throws Exception {
        when(flightService.update(eq(99L), any(FlightRequest.class)))
                .thenThrow(new FlightNotFoundException(99L));

        mockMvc.perform(put("/api/flights/99").contentType(MediaType.APPLICATION_JSON).content(json(validBody())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("FlightNotFound"))
                .andExpect(jsonPath("$.message").value("Flight 99 does not exist"))
                .andExpect(jsonPath("$.fieldErrors").isMap())
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    private static Map<String, Object> validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flightNumber", "BA117");
        body.put("origin", "LHR");
        body.put("destination", "JFK");
        body.put("departureTime", "2030-06-01T10:15:30");
        body.put("arrivalTime", "2030-06-01T18:45:30");
        return body;
    }

    private static String json(Map<String, Object> body) throws Exception {
        return JSON.writeValueAsString(body);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q test -Dtest=FlightControllerTest`
Expected: FAIL — compilation error, `cannot find symbol: class FlightController`.

- [ ] **Step 3: Create `ApiError`**

```java
package com.flightcontrol.dto;

import java.time.Instant;
import java.util.Map;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        Map<String, String> fieldErrors
) {
}
```

- [ ] **Step 4: Create `GlobalExceptionHandler`**

```java
package com.flightcontrol.exception;

import com.flightcontrol.dto.ApiError;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single translation point from exceptions to the ApiError contract.
 * Framework exceptions already mapped by ResponseEntityExceptionHandler are customised by
 * overriding its protected methods, never by a second @ExceptionHandler for the same type.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return respond(HttpStatus.BAD_REQUEST, "ValidationFailed", "Request validation failed", fieldErrors, headers);
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    public ResponseEntity<Object> handleBusinessRuleViolation(BusinessRuleViolationException ex) {
        return respond(HttpStatus.BAD_REQUEST, "BusinessRuleViolation", ex.getMessage(),
                ex.getFieldErrors(), new HttpHeaders());
    }

    @ExceptionHandler(FlightNotFoundException.class)
    public ResponseEntity<Object> handleFlightNotFound(FlightNotFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, "FlightNotFound", ex.getMessage(), Map.of(), new HttpHeaders());
    }

    @ExceptionHandler(DuplicateFlightNumberException.class)
    public ResponseEntity<Object> handleDuplicateFlightNumber(DuplicateFlightNumberException ex) {
        return respond(HttpStatus.CONFLICT, "DuplicateFlightNumber", ex.getMessage(),
                Map.of("flightNumber", "already exists"), new HttpHeaders());
    }

    private ResponseEntity<Object> respond(HttpStatus status, String error, String message,
                                           Map<String, String> fieldErrors, HttpHeaders headers) {
        ApiError body = new ApiError(Instant.now(clock), status.value(), error, message, fieldErrors);
        return ResponseEntity.status(status).headers(headers).body(body);
    }
}
```

- [ ] **Step 5: Create `FlightController`**

```java
package com.flightcontrol.controller;

import com.flightcontrol.dto.FlightRequest;
import com.flightcontrol.dto.FlightResponse;
import com.flightcontrol.service.FlightService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/flights")
public class FlightController {

    private final FlightService flightService;

    public FlightController(FlightService flightService) {
        this.flightService = flightService;
    }

    @PostMapping
    public ResponseEntity<FlightResponse> create(@Valid @RequestBody FlightRequest request) {
        FlightResponse created = flightService.create(request);
        return ResponseEntity.created(URI.create("/api/flights/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public FlightResponse update(@PathVariable Long id, @Valid @RequestBody FlightRequest request) {
        return flightService.update(id, request);
    }
}
```

- [ ] **Step 6: Run the controller tests**

Run: `mvn -q test -Dtest=FlightControllerTest`
Expected: PASS (8 plain tests + 2 × 19 parameterized cases).

- [ ] **Step 7: Run the whole suite**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/flightcontrol/dto/ApiError.java src/main/java/com/flightcontrol/exception/GlobalExceptionHandler.java src/main/java/com/flightcontrol/controller src/test/java/com/flightcontrol/controller
git commit -m "Add create/update flight endpoints with structured error responses (Story 2)" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Error contract hardening — malformed input, framework errors, never a bare 500

**Files:**
- Modify: `src/main/java/com/flightcontrol/exception/GlobalExceptionHandler.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/flightcontrol/controller/FlightControllerTest.java`

**Interfaces:**
- Consumes: `GlobalExceptionHandler.respond(HttpStatus, String, String, Map<String, String>, HttpHeaders)` from Task 4.
- Produces: no new public API. Every response from the application, including Spring's own 4xx errors and unexpected exceptions, now uses `ApiError`.

Note: the spec names Jackson's `InvalidFormatException`; this task matches its superclass `MismatchedInputException`, which also covers a wrong JSON token type (e.g. a number where a date string is expected). Both carry the JSON path.

- [ ] **Step 1: Write the failing tests**

Add these imports to `FlightControllerTest`:

```java
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
```

Add these tests before the `validBody` helper:

```java
    @Test
    void rejectsUnparseableJsonBody() throws Exception {
        mockMvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("MalformedRequest"))
                .andExpect(jsonPath("$.message").value("Request body is missing or malformed"))
                .andExpect(jsonPath("$.fieldErrors").isMap())
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    void rejectsMissingBody() throws Exception {
        mockMvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MalformedRequest"));
    }

    @Test
    void namesTheFieldWhenADateCannotBeParsed() throws Exception {
        Map<String, Object> body = validBody();
        body.put("departureTime", "tomorrow morning");

        mockMvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MalformedRequest"))
                .andExpect(jsonPath("$.fieldErrors.*", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors.departureTime").value("invalid format"))
                .andExpect(content().string(not(containsString("LocalDateTime"))));
    }

    @Test
    void rejectsNonNumericFlightId() throws Exception {
        mockMvc.perform(put("/api/flights/abc").contentType(MediaType.APPLICATION_JSON).content(json(validBody())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MalformedRequest"))
                .andExpect(jsonPath("$.message").value("Request parameter is malformed"))
                .andExpect(jsonPath("$.fieldErrors.id").value("invalid value"));
        verifyNoInteractions(flightService);
    }

    @Test
    void reportsUnsupportedHttpMethodInTheSameErrorShape() throws Exception {
        mockMvc.perform(delete("/api/flights"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("MethodNotAllowed"))
                .andExpect(jsonPath("$.timestamp").value("2030-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.fieldErrors").isMap());
    }

    @Test
    void hidesInternalDetailsOfUnexpectedErrors() throws Exception {
        when(flightService.create(any(FlightRequest.class)))
                .thenThrow(new IllegalStateException("connection pool secret-detail exhausted"));

        mockMvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON).content(json(validBody())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("InternalError"))
                .andExpect(jsonPath("$.message").value("unexpected error"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(content().string(not(containsString("secret-detail"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `mvn -q test -Dtest=FlightControllerTest`
Expected: FAIL — the six new tests fail. Typical symptoms: `No value at JSON path "$.error"` (Spring's default `ProblemDetail` body or an empty body), and `hidesInternalDetailsOfUnexpectedErrors` errors with a propagated `IllegalStateException`.

- [ ] **Step 3: Extend `GlobalExceptionHandler`**

Add imports:

```java
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Objects;
import java.util.stream.Collectors;
```

Add these methods after `handleMethodArgumentNotValid`:

```java
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        if (ex.getCause() instanceof MismatchedInputException mismatched) {
            String field = mismatched.getPath().stream()
                    .map(JsonMappingException.Reference::getFieldName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("."));
            if (!field.isEmpty()) {
                fieldErrors.put(field, "invalid format");
            }
        }
        // Never echo the parser's message: it leaks Java type names.
        return respond(HttpStatus.BAD_REQUEST, "MalformedRequest", "Request body is missing or malformed",
                fieldErrors, headers);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex,
                                                        HttpHeaders headers,
                                                        HttpStatusCode status,
                                                        WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName()
                : ex.getPropertyName();
        Map<String, String> fieldErrors = name == null ? Map.of() : Map.of(name, "invalid value");
        return respond(HttpStatus.BAD_REQUEST, "MalformedRequest", "Request parameter is malformed",
                fieldErrors, headers);
    }

    /** Every other framework error (405, 415, 406, unknown path, ...) funnels through here. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             @Nullable Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        String message = body instanceof ProblemDetail problem && problem.getDetail() != null
                ? problem.getDetail()
                : status.getReasonPhrase();
        return respond(status, status.getReasonPhrase().replace(" ", ""), message, Map.of(), headers);
    }
```

Add this method after `handleDuplicateFlightNumber`:

```java
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        logger.error("Unexpected error", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "InternalError", "unexpected error",
                Map.of(), new HttpHeaders());
    }
```

(`logger` is the protected `Log` field inherited from `ResponseEntityExceptionHandler`.)

- [ ] **Step 4: Run the controller tests**

Run: `mvn -q test -Dtest=FlightControllerTest`
Expected: PASS — all Task 4 tests plus the six new ones.

- [ ] **Step 5: Stop the fallback error page from exposing details**

Append to `src/main/resources/application.properties`:

```properties

# Anything that escapes the @RestControllerAdvice must still never expose internals.
server.error.include-stacktrace=never
server.error.include-message=never
```

- [ ] **Step 6: Run the whole suite**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/flightcontrol/exception/GlobalExceptionHandler.java src/main/resources/application.properties src/test/java/com/flightcontrol/controller/FlightControllerTest.java
git commit -m "Translate malformed requests, framework errors and unexpected failures into ApiError (Story 2)" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Real-stack integration, unique-constraint race backstop, final verification

**Files:**
- Modify: `src/main/java/com/flightcontrol/exception/GlobalExceptionHandler.java`
- Test: `src/test/java/com/flightcontrol/FlightApiIntegrationTest.java`

**Interfaces:**
- Consumes: the full stack from Tasks 1–5; constraint name `uk_flights_flight_number` from `schema.sql`; `GlobalExceptionHandler.respond(...)`.
- Produces: `public ResponseEntity<Object> GlobalExceptionHandler.handleDataIntegrityViolation(DataIntegrityViolationException ex)`.

Why the race test calls the handler directly: two concurrent requests cannot be reproduced deterministically through MockMvc. Instead the test provokes a **real** H2 unique-key violation through the repository and hands that real exception to the handler, proving the constraint-name match works against the actual driver message.

- [ ] **Step 1: Write the failing integration test**

Create `src/test/java/com/flightcontrol/FlightApiIntegrationTest.java`:

```java
package com.flightcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flightcontrol.domain.Flight;
import com.flightcontrol.dto.ApiError;
import com.flightcontrol.exception.GlobalExceptionHandler;
import com.flightcontrol.repository.FlightRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real stack end to end. Transactional so every test rolls back
 * and nothing depends on, or leaks into, other tests or seeded rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FlightApiIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MockMvc mockMvc;
    private final FlightRepository flightRepository;
    private final GlobalExceptionHandler exceptionHandler;

    @Autowired
    FlightApiIntegrationTest(MockMvc mockMvc, FlightRepository flightRepository,
                             GlobalExceptionHandler exceptionHandler) {
        this.mockMvc = mockMvc;
        this.flightRepository = flightRepository;
        this.exceptionHandler = exceptionHandler;
    }

    @Test
    void storesCreatedFlightInUpperCaseAsScheduled() throws Exception {
        mockMvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON)
                        .content(body("xy123", "lhr", "jfk")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.flightNumber").value("XY123"))
                .andExpect(jsonPath("$.origin").value("LHR"))
                .andExpect(jsonPath("$.destination").value("JFK"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void rejectsFlightNumberDifferingOnlyByCaseWithConflict() throws Exception {
        mockMvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON)
                        .content(body("XY123", "LHR", "JFK")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON)
                        .content(body("xy123", "CDG", "SFO")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("DuplicateFlightNumber"))
                .andExpect(jsonPath("$.message").value("Flight number XY123 already exists"))
                .andExpect(jsonPath("$.fieldErrors.flightNumber").value("already exists"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void rejectsPastDepartureAgainstTheRealClock() throws Exception {
        LocalDateTime past = LocalDateTime.now().minusHours(1).truncatedTo(ChronoUnit.SECONDS);

        mockMvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON)
                        .content(body("XY124", "LHR", "JFK", past, past.plusHours(8))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BusinessRuleViolation"))
                .andExpect(jsonPath("$.fieldErrors.departureTime").value("must be in the future"));
    }

    @Test
    void updatesFlightKeepingItsOwnNumberButNotAnotherFlights() throws Exception {
        String created = mockMvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON)
                        .content(body("XY125", "LHR", "JFK")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(created, "$.id");
        mockMvc.perform(post("/api/flights").contentType(MediaType.APPLICATION_JSON)
                        .content(body("XY126", "LHR", "JFK")))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/api/flights/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(body("xy125", "LHR", "BOS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flightNumber").value("XY125"))
                .andExpect(jsonPath("$.destination").value("BOS"));

        mockMvc.perform(put("/api/flights/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(body("xy126", "LHR", "BOS")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DuplicateFlightNumber"));
    }

    @Test
    void returnsNotFoundWhenUpdatingUnknownFlight() throws Exception {
        mockMvc.perform(put("/api/flights/987654321").contentType(MediaType.APPLICATION_JSON)
                        .content(body("XY127", "LHR", "JFK")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("FlightNotFound"));
    }

    @Test
    void translatesRealUniqueConstraintViolationIntoConflict() {
        LocalDateTime departure = LocalDateTime.now().plusDays(1);
        flightRepository.saveAndFlush(new Flight("ZZ900", "LHR", "JFK", departure, departure.plusHours(8)));

        DataIntegrityViolationException violation = catchThrowableOfType(
                DataIntegrityViolationException.class,
                () -> flightRepository.saveAndFlush(
                        new Flight("ZZ900", "CDG", "SFO", departure, departure.plusHours(8))));
        assertThat(violation).isNotNull();

        ResponseEntity<Object> response = exceptionHandler.handleDataIntegrityViolation(violation);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiError error = (ApiError) response.getBody();
        assertThat(error.error()).isEqualTo("DuplicateFlightNumber");
        assertThat(error.fieldErrors()).containsOnlyKeys("flightNumber");
    }

    private static String body(String flightNumber, String origin, String destination) throws Exception {
        LocalDateTime departure = LocalDateTime.now().plusDays(1).truncatedTo(ChronoUnit.SECONDS);
        return body(flightNumber, origin, destination, departure, departure.plusHours(8));
    }

    private static String body(String flightNumber, String origin, String destination,
                               LocalDateTime departure, LocalDateTime arrival) throws Exception {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("flightNumber", flightNumber);
        json.put("origin", origin);
        json.put("destination", destination);
        json.put("departureTime", departure.toString());
        json.put("arrivalTime", arrival.toString());
        return JSON.writeValueAsString(json);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q test -Dtest=FlightApiIntegrationTest`
Expected: FAIL — compilation error, `cannot find symbol: method handleDataIntegrityViolation`.

- [ ] **Step 3: Add the backstop handler**

In `GlobalExceptionHandler`, add imports:

```java
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Locale;
```

Add after `handleDuplicateFlightNumber` (before `handleUnexpected`):

```java
    /**
     * Backstop for two concurrent requests that both pass the service's uniqueness check:
     * the database constraint rejects the loser, and it must still be a 409, not a 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String cause = String.valueOf(ex.getMostSpecificCause().getMessage()).toUpperCase(Locale.ROOT);
        if (cause.contains("UK_FLIGHTS_FLIGHT_NUMBER")) {
            return respond(HttpStatus.CONFLICT, "DuplicateFlightNumber", "Flight number already exists",
                    Map.of("flightNumber", "already exists"), new HttpHeaders());
        }
        return handleUnexpected(ex);
    }
```

- [ ] **Step 4: Run the integration test**

Run: `mvn -q test -Dtest=FlightApiIntegrationTest`
Expected: PASS (6 tests).

If `translatesRealUniqueConstraintViolationIntoConflict` fails with status 500, the H2 message does not contain the constraint name as assumed. Print `violation.getMostSpecificCause().getMessage()` in the test, read the actual identifier H2 reports for `uk_flights_flight_number`, and match on that. Do not weaken the check to "any integrity violation is a duplicate".

- [ ] **Step 5: Full build**

Run: `mvn clean verify`
Expected: `BUILD SUCCESS`, zero failures, zero errors.

- [ ] **Step 6: Smoke-check the running app**

Run in one terminal: `mvn -q spring-boot:run`
In another:

```bash
curl -s -i -X POST localhost:8080/api/flights -H 'Content-Type: application/json' \
  -d '{"flightNumber":"ba117","origin":"LHR","destination":"LHR","departureTime":"2020-01-01T10:00:00","arrivalTime":"2019-01-01T10:00:00"}'
```

Expected: `HTTP/1.1 400`, `"error":"BusinessRuleViolation"`, and `fieldErrors` containing `departureTime`, `arrivalTime` and `destination`. (`BA117` is a seeded flight, but data rules fail first, so this is a 400, not a 409.)

```bash
curl -s -i -X POST localhost:8080/api/flights -H 'Content-Type: application/json' \
  -d "{\"flightNumber\":\"ba117\",\"origin\":\"LHR\",\"destination\":\"JFK\",\"departureTime\":\"$(date -v+1d +%Y-%m-%dT%H:%M:%S)\",\"arrivalTime\":\"$(date -v+2d +%Y-%m-%dT%H:%M:%S)\"}"
```

Expected: `HTTP/1.1 409`, `"error":"DuplicateFlightNumber"`, `"fieldErrors":{"flightNumber":"already exists"}`. (`date -v` is the macOS form; on Linux use `date -d '+1 day' +%Y-%m-%dT%H:%M:%S`.) Stop the app afterwards.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/flightcontrol/exception/GlobalExceptionHandler.java src/test/java/com/flightcontrol/FlightApiIntegrationTest.java
git commit -m "Map unique-constraint races to 409 and cover the real stack end to end (Story 2)" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## Spec Coverage Map

| Spec requirement | Task |
|---|---|
| Past departure → 400 `departureTime` | 2 (create), 3 (update), 6 (real clock) |
| Arrival strictly after departure → 400 `arrivalTime` | 2, 3 |
| Origin ≠ destination → 400 `destination` | 2, 3 |
| Case-insensitive unique flight number → 409 `flightNumber` | 1 (DB + queries), 2, 3, 6 |
| Aggregated data-rule errors; uniqueness only after they pass | 2 |
| Uppercase normalization + schema `CHECK` | 1, 2 |
| Field-shape rules on DTO, one message per field | 2 (DTO), 4 (tests) |
| `POST` 201 + Location, `PUT` 200, 404 on unknown id | 3, 4 |
| Single advice, `ApiError` shape, `fieldErrors` always present | 4 |
| Malformed body / bad date / bad path id → 400 | 5 |
| Framework errors in same shape; 500 hides internals | 5 |
| `server.error.*` properties | 5 |
| Unique-constraint race → 409 | 6 |
| `@SpringBootTest` real-stack test; `mvn clean verify` green | 6 |
