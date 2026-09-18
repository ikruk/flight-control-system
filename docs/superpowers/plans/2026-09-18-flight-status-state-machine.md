# Flight Status State Machine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a flight change status only through allowed transitions, exposed as a narrow `PATCH /api/flights/{id}/status` endpoint whose responses tell the client where the flight can go next.

**Architecture:** The transition table is an exhaustive `switch` on `FlightStatus` with no `default`, so a new status cannot be added without declaring its transitions. `FlightService` is the only layer that decides legality; the controller maps request to service call to `FlightResponse`; one `@RestControllerAdvice` turns domain exceptions and binding failures into a single JSON error shape.

**Tech Stack:** Java 21, Spring Boot 3.5.6, Spring Data JPA, H2 (in-memory), Bean Validation, JUnit 5, MockMvc, AssertJ, Maven.

**Spec:** `docs/superpowers/specs/2026-09-18-flight-status-state-machine-design.md`

**Baseline (read this before Task 2).** Story 2's service-level work is already
committed (`0cd553a`). Existing and NOT to be rewritten:

- `FlightService(FlightRepository, Clock)` with `create(FlightRequest)` returning
  `FlightResponse`. Story 3 adds a method to this class; it does not create it.
- `FlightResponse` (no `allowedNextStatuses` yet), `FlightRequest`, `ClockConfig`.
- `BusinessRuleViolationException`, `DuplicateFlightNumberException` — leave unmapped.
- `FlightServiceTest`, Mockito-based. Story 3 adds a separate test class.
- `spring-boot-starter-validation` is already a dependency. Do not re-add it.

Consequences, decided with the user and reflected in the tasks below:

1. `transitionStatus` returns `FlightResponse`, matching `create`.
2. Transition service tests live in a new `FlightTransitionServiceTest`.
3. The advice maps Story 3's exceptions only.

## Global Constraints

- Layering per `CLAUDE.md`: `controller/` HTTP only, `service/` all business rules, `repository/` no logic, `domain/` entities and enums, `dto/` request/response records, `exception/` domain exceptions + one advice.
- Constructor injection only. No field `@Autowired`.
- Entities never cross the controller boundary — map to DTOs.
- Controllers contain no `if` on business state.
- No `Optional` as a method parameter; resolve repository `Optional`s in the service.
- `spring.jpa.hibernate.ddl-auto=none` stays. Schema changes go in `schema.sql`. **This story changes no schema.**
- Transition rules are encoded exactly once, on `FlightStatus`.
- Test names read as sentences. Tests build their own data and never depend on rows from `data.sql` (the existing `SeedDataTest` is the sole exception and is not touched here).
- TDD is mandatory: write the failing test, watch it fail for the right reason, then write the minimum code to pass.
- Error bodies follow the `ApiErrorResponse` shape from Task 4. Never a bare 500 or a stack trace.
- Package root: `com.flightcontrol`.
- Out of scope, do not add: create/update/delete/search endpoints, `editable`/`deletable` response fields, optimistic locking (`@Version`).

**Deviation from the spec's sketch, already decided:** the spec showed `new IllegalStatusTransitionException(flight, target)`. The approved message text never mentions the target, so the exception takes `Flight` only — an unused constructor parameter would be dead weight. Message text itself is unchanged from the spec.

**Green baseline:** `mvn clean verify` passes today with Story 1 + Story 2 tests. Run it before Task 1 and record the test count; every task ends green with that count only going up.

---

### Task 1: Transition table on `FlightStatus`

**Files:**
- Modify: `src/main/java/com/flightcontrol/domain/FlightStatus.java`
- Test: `src/test/java/com/flightcontrol/domain/FlightStatusTest.java` (exists, has one test — add to it)

**Interfaces:**
- Consumes: nothing.
- Produces: `Set<FlightStatus> FlightStatus.allowedNextStatuses()`, `boolean FlightStatus.canTransitionTo(FlightStatus target)`, `boolean FlightStatus.isTerminal()`.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/flightcontrol/domain/FlightStatusTest.java`, keeping the existing `definesExactlyTheSixLifecycleStatuses` test. The expected table below is written out **literally** — do not derive it from `allowedNextStatuses()`, or the test only asserts the production code equals itself.

```java
package com.flightcontrol.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.flightcontrol.domain.FlightStatus.CANCELLED;
import static com.flightcontrol.domain.FlightStatus.DELAYED;
import static com.flightcontrol.domain.FlightStatus.DEPARTED;
import static com.flightcontrol.domain.FlightStatus.IN_AIR;
import static com.flightcontrol.domain.FlightStatus.LANDED;
import static com.flightcontrol.domain.FlightStatus.SCHEDULED;
import static org.assertj.core.api.Assertions.assertThat;

class FlightStatusTest {

    /** The transition table, written out independently of the production code. */
    private static final Map<FlightStatus, Set<FlightStatus>> LEGAL_TRANSITIONS = Map.of(
            SCHEDULED, Set.of(DELAYED, DEPARTED, CANCELLED),
            DELAYED, Set.of(DEPARTED, CANCELLED),
            DEPARTED, Set.of(IN_AIR),
            IN_AIR, Set.of(LANDED),
            LANDED, Set.of(),
            CANCELLED, Set.of());

    @Test
    void definesExactlyTheSixLifecycleStatuses() {
        assertThat(FlightStatus.values()).containsExactly(
                FlightStatus.SCHEDULED,
                FlightStatus.DELAYED,
                FlightStatus.DEPARTED,
                FlightStatus.IN_AIR,
                FlightStatus.LANDED,
                FlightStatus.CANCELLED);
    }

    @ParameterizedTest(name = "{0} -> {1} legal={2}")
    @MethodSource("everyStatusPair")
    void enforcesTheTransitionTableForEveryStatusPair(FlightStatus from, FlightStatus to, boolean legal) {
        assertThat(from.canTransitionTo(to)).isEqualTo(legal);
    }

    @ParameterizedTest
    @EnumSource(FlightStatus.class)
    void exposesExactlyTheAllowedTargetsForEachStatus(FlightStatus status) {
        assertThat(status.allowedNextStatuses())
                .containsExactlyInAnyOrderElementsOf(LEGAL_TRANSITIONS.get(status));
    }

    @Test
    void listsAllowedTargetsInEnumDeclarationOrder() {
        assertThat(SCHEDULED.allowedNextStatuses()).containsExactly(DELAYED, DEPARTED, CANCELLED);
    }

    @Test
    void treatsLandedAndCancelledAsTerminal() {
        assertThat(LANDED.isTerminal()).isTrue();
        assertThat(CANCELLED.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = FlightStatus.class, names = {"SCHEDULED", "DELAYED", "DEPARTED", "IN_AIR"})
    void treatsEveryNonTerminalStatusAsNonTerminal(FlightStatus status) {
        assertThat(status.isTerminal()).isFalse();
    }

    private static Stream<Arguments> everyStatusPair() {
        return Arrays.stream(FlightStatus.values()).flatMap(from ->
                Arrays.stream(FlightStatus.values()).map(to ->
                        Arguments.of(from, to, LEGAL_TRANSITIONS.get(from).contains(to))));
    }
}
```

Note what `everyStatusPair` covers: all 36 ordered pairs, so self-transitions such as `SCHEDULED -> SCHEDULED` are asserted illegal without a special-case test.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=FlightStatusTest`
Expected: COMPILATION FAILURE — `cannot find symbol: method canTransitionTo(FlightStatus)`, `allowedNextStatuses()`, `isTerminal()`. A compile error is the correct RED here: the methods do not exist yet.

- [ ] **Step 3: Write the minimal implementation**

Replace `src/main/java/com/flightcontrol/domain/FlightStatus.java` with:

```java
package com.flightcontrol.domain;

import java.util.EnumSet;
import java.util.Set;

import static java.util.Collections.unmodifiableSet;

public enum FlightStatus {
    SCHEDULED,
    DELAYED,
    DEPARTED,
    IN_AIR,
    LANDED,
    CANCELLED;

    /**
     * The transition table, encoded once. The switch is exhaustive with no default,
     * so a new status will not compile until its transitions are declared here.
     * EnumSet iterates in declaration order, which keeps the order stable for clients.
     */
    public Set<FlightStatus> allowedNextStatuses() {
        return switch (this) {
            case SCHEDULED -> unmodifiableSet(EnumSet.of(DELAYED, DEPARTED, CANCELLED));
            case DELAYED -> unmodifiableSet(EnumSet.of(DEPARTED, CANCELLED));
            case DEPARTED -> unmodifiableSet(EnumSet.of(IN_AIR));
            case IN_AIR -> unmodifiableSet(EnumSet.of(LANDED));
            case LANDED, CANCELLED -> unmodifiableSet(EnumSet.noneOf(FlightStatus.class));
        };
    }

    public boolean canTransitionTo(FlightStatus target) {
        return allowedNextStatuses().contains(target);
    }

    public boolean isTerminal() {
        return allowedNextStatuses().isEmpty();
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -Dtest=FlightStatusTest`
Expected: PASS, 49 tests (1 + 36 pairs + 6 per-status + 1 order + 1 terminal + 4 non-terminal).

- [ ] **Step 5: Run the whole suite**

Run: `mvn test`
Expected: PASS, no failures. The Story 1 tests must still be green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/flightcontrol/domain/FlightStatus.java src/test/java/com/flightcontrol/domain/FlightStatusTest.java
git commit -m "Add transition table to FlightStatus (Story 3)"
```

---

### Task 2: `FlightService.transitionStatus` and its exceptions

**Files:**
- Create: `src/main/java/com/flightcontrol/exception/FlightNotFoundException.java`
- Create: `src/main/java/com/flightcontrol/exception/IllegalStatusTransitionException.java`
- Modify: `src/main/java/com/flightcontrol/service/FlightService.java` (add one method; leave `create` and its helpers untouched)
- Test: `src/test/java/com/flightcontrol/service/FlightTransitionServiceTest.java` (new file — do not edit the existing `FlightServiceTest`)

**Interfaces:**
- Consumes: `FlightStatus.canTransitionTo(FlightStatus)`, `FlightStatus.isTerminal()`, `FlightStatus.allowedNextStatuses()` (Task 1); `FlightRepository`, `Flight` (Story 1); `FlightResponse.from(Flight)`, `ClockConfig` (Story 2).
- Produces: `FlightResponse FlightService.transitionStatus(Long id, FlightStatus target)`; `FlightNotFoundException(Long id)`; `IllegalStatusTransitionException(Flight flight)`. Both exceptions extend `RuntimeException` and carry a finished message in `getMessage()`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/flightcontrol/service/FlightTransitionServiceTest.java`. This is a **new file**; Story 2's `FlightServiceTest` in the same package is not touched.

```java
package com.flightcontrol.service;

import com.flightcontrol.config.ClockConfig;
import com.flightcontrol.domain.Flight;
import com.flightcontrol.domain.FlightStatus;
import com.flightcontrol.dto.FlightResponse;
import com.flightcontrol.exception.FlightNotFoundException;
import com.flightcontrol.exception.IllegalStatusTransitionException;
import com.flightcontrol.repository.FlightRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({FlightService.class, ClockConfig.class})
class FlightTransitionServiceTest {

    private final FlightService flightService;
    private final FlightRepository flightRepository;
    private final TestEntityManager entityManager;

    @Autowired
    FlightTransitionServiceTest(FlightService flightService, FlightRepository flightRepository,
                                TestEntityManager entityManager) {
        this.flightService = flightService;
        this.flightRepository = flightRepository;
        this.entityManager = entityManager;
    }

    @Test
    void appliesAndPersistsALegalTransition() {
        Long id = givenFlight("SVC100", FlightStatus.SCHEDULED).getId();

        FlightResponse response = flightService.transitionStatus(id, FlightStatus.DEPARTED);
        entityManager.flush();
        entityManager.clear();

        assertThat(response.status()).isEqualTo(FlightStatus.DEPARTED);
        assertThat(flightRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(FlightStatus.DEPARTED);
    }


    @Test
    void refreshesUpdatedAtWhenTransitioning() {
        Flight flight = givenFlight("SVC110", FlightStatus.SCHEDULED);
        LocalDateTime before = flight.getUpdatedAt();

        flightService.transitionStatus(flight.getId(), FlightStatus.DELAYED);
        entityManager.flush();

        assertThat(flight.getUpdatedAt()).isAfter(before);
    }

    @Test
    void rejectsATransitionThatIsNotInTheTable() {
        Long id = givenFlight("SVC200", FlightStatus.SCHEDULED).getId();

        assertThatThrownBy(() -> flightService.transitionStatus(id, FlightStatus.LANDED))
                .isInstanceOf(IllegalStatusTransitionException.class)
                .hasMessage("Flight SVC200 is SCHEDULED; allowed transitions: DELAYED, DEPARTED, CANCELLED");
    }

    @Test
    void rejectsATransitionToTheSameStatus() {
        Long id = givenFlight("SVC210", FlightStatus.SCHEDULED).getId();

        assertThatThrownBy(() -> flightService.transitionStatus(id, FlightStatus.SCHEDULED))
                .isInstanceOf(IllegalStatusTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(FlightStatus.class)
    void rejectsEveryTransitionOutOfLanded(FlightStatus target) {
        Long id = givenFlight("SVC300", FlightStatus.LANDED).getId();

        assertThatThrownBy(() -> flightService.transitionStatus(id, target))
                .isInstanceOf(IllegalStatusTransitionException.class)
                .hasMessage("Flight SVC300 is LANDED; LANDED is terminal, no transitions are allowed");
    }

    @ParameterizedTest
    @EnumSource(FlightStatus.class)
    void rejectsEveryTransitionOutOfCancelled(FlightStatus target) {
        Long id = givenFlight("SVC310", FlightStatus.CANCELLED).getId();

        assertThatThrownBy(() -> flightService.transitionStatus(id, target))
                .isInstanceOf(IllegalStatusTransitionException.class)
                .hasMessage("Flight SVC310 is CANCELLED; CANCELLED is terminal, no transitions are allowed");
    }

    @Test
    void leavesTheStoredStatusUnchangedWhenATransitionIsRejected() {
        Long id = givenFlight("SVC400", FlightStatus.IN_AIR).getId();

        assertThatThrownBy(() -> flightService.transitionStatus(id, FlightStatus.SCHEDULED))
                .isInstanceOf(IllegalStatusTransitionException.class);
        entityManager.flush();
        entityManager.clear();

        assertThat(flightRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(FlightStatus.IN_AIR);
    }

    @Test
    void rejectsATransitionOnAFlightThatDoesNotExist() {
        assertThatThrownBy(() -> flightService.transitionStatus(9_999L, FlightStatus.DEPARTED))
                .isInstanceOf(FlightNotFoundException.class)
                .hasMessage("Flight 9999 not found");
    }

    private Flight givenFlight(String flightNumber, FlightStatus status) {
        LocalDateTime departure = LocalDateTime.now().plusDays(1);
        Flight flight = new Flight(flightNumber, "JFK", "LHR", departure, departure.plusHours(3));
        flight.setStatus(status);
        Flight saved = flightRepository.saveAndFlush(flight);
        entityManager.clear();
        return flightRepository.findById(saved.getId()).orElseThrow();
    }
}
```

Three notes for the implementer:

- `@DataJpaTest` gives a real repository against real H2, and `@Import({FlightService.class, ClockConfig.class})` adds the real service. `ClockConfig` is required: `FlightService`'s constructor takes a `Clock`, and a `@DataJpaTest` slice does not pick up `@Configuration` classes on its own. Without it the context fails with `No qualifying bean of type 'java.time.Clock'`.
- No mocks — these tests exercise the code that ships. Story 2's `FlightServiceTest` mocks the repository; that is its choice, not a pattern to copy here.
- `hasMessage(...)` pins the exact wording because Story 3 requires the message to name the current status and the allowed set.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=FlightTransitionServiceTest`
Expected: COMPILATION FAILURE — `cannot find symbol: class FlightNotFoundException`, `class IllegalStatusTransitionException`, and `method transitionStatus(Long,FlightStatus)` on `FlightService`.

- [ ] **Step 3: Write the minimal implementation**

Create `src/main/java/com/flightcontrol/exception/FlightNotFoundException.java`:

```java
package com.flightcontrol.exception;

public class FlightNotFoundException extends RuntimeException {

    public FlightNotFoundException(Long id) {
        super("Flight %d not found".formatted(id));
    }
}
```

Create `src/main/java/com/flightcontrol/exception/IllegalStatusTransitionException.java`:

```java
package com.flightcontrol.exception;

import com.flightcontrol.domain.Flight;
import com.flightcontrol.domain.FlightStatus;

import java.util.stream.Collectors;

public class IllegalStatusTransitionException extends RuntimeException {

    public IllegalStatusTransitionException(Flight flight) {
        super(describe(flight));
    }

    private static String describe(Flight flight) {
        FlightStatus current = flight.getStatus();
        if (current.isTerminal()) {
            return "Flight %s is %s; %s is terminal, no transitions are allowed"
                    .formatted(flight.getFlightNumber(), current, current);
        }
        return "Flight %s is %s; allowed transitions: %s".formatted(
                flight.getFlightNumber(),
                current,
                current.allowedNextStatuses().stream()
                        .map(Enum::name)
                        .collect(Collectors.joining(", ")));
    }
}
```

Modify `src/main/java/com/flightcontrol/service/FlightService.java`. Add these imports alongside the existing ones:

```java
import com.flightcontrol.domain.FlightStatus;
import com.flightcontrol.exception.FlightNotFoundException;
import com.flightcontrol.exception.IllegalStatusTransitionException;
```

Then add this method directly after `create`, leaving `create`, `normalize` and `validateDataRules` exactly as they are:

```java
    @Transactional
    public FlightResponse transitionStatus(Long id, FlightStatus target) {
        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));
        if (!flight.getStatus().canTransitionTo(target)) {
            throw new IllegalStatusTransitionException(flight);
        }
        flight.setStatus(target);
        return FlightResponse.from(flight);
    }
```

`Flight`, `FlightResponse`, `FlightRepository`, `@Service` and `@Transactional` are already imported by the existing file. Do not change the constructor — it takes `(FlightRepository, Clock)` and the `Clock` belongs to `create`'s validation.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=FlightTransitionServiceTest`
Expected: PASS, 18 tests.

If `refreshesUpdatedAtWhenTransitioning` fails on an equal timestamp, do not weaken the assertion — check that `Flight.onUpdate()` is still annotated `@PreUpdate` and that the test flushes.

- [ ] **Step 5: Run the whole suite**

Run: `mvn test`
Expected: PASS. Story 2's `FlightServiceTest` must still be green — if it is not, `FlightService` was edited beyond adding the new method.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/flightcontrol/exception src/main/java/com/flightcontrol/service/FlightService.java src/test/java/com/flightcontrol/service/FlightTransitionServiceTest.java
git commit -m "Add FlightService.transitionStatus with transition guards (Story 3)"
```

---

### Task 3: `PATCH /api/flights/{id}/status` happy path

**Files:**
- Modify: `src/main/java/com/flightcontrol/dto/FlightResponse.java` (add one field)
- Create: `src/main/java/com/flightcontrol/dto/StatusTransitionRequest.java`
- Create: `src/main/java/com/flightcontrol/controller/FlightStatusController.java`
- Test: `src/test/java/com/flightcontrol/controller/FlightStatusControllerTest.java`

No `pom.xml` change — `spring-boot-starter-validation` arrived with Story 2.

**Interfaces:**
- Consumes: `FlightService.transitionStatus(Long, FlightStatus)` returning `FlightResponse` (Task 2); `FlightStatus.allowedNextStatuses()` (Task 1).
- Produces: `FlightResponse` gains `List<FlightStatus> allowedNextStatuses` as its eighth component, between `status` and `createdAt`; `StatusTransitionRequest(FlightStatus status)`; the endpoint `PATCH /api/flights/{id}/status`.

Error-path tests (404, 409, 400) belong to Task 4 — this task stops at 200.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/flightcontrol/controller/FlightStatusControllerTest.java`:

```java
package com.flightcontrol.controller;

import com.flightcontrol.domain.Flight;
import com.flightcontrol.domain.FlightStatus;
import com.flightcontrol.repository.FlightRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FlightStatusControllerTest {

    private final MockMvc mockMvc;
    private final FlightRepository flightRepository;

    @Autowired
    FlightStatusControllerTest(MockMvc mockMvc, FlightRepository flightRepository) {
        this.mockMvc = mockMvc;
        this.flightRepository = flightRepository;
    }

    @Test
    void appliesALegalTransitionAndReturnsTheUpdatedFlight() throws Exception {
        Long id = givenFlight("WEB100", FlightStatus.SCHEDULED);

        mockMvc.perform(patch("/api/flights/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DEPARTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.flightNumber").value("WEB100"))
                .andExpect(jsonPath("$.status").value("DEPARTED"))
                .andExpect(jsonPath("$.allowedNextStatuses").value("IN_AIR"))
                .andExpect(jsonPath("$.origin").value("JFK"))
                .andExpect(jsonPath("$.destination").value("LHR"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void listsEveryAllowedNextStatusInDeclarationOrder() throws Exception {
        Long id = givenFlight("WEB110", FlightStatus.DEPARTED);

        mockMvc.perform(patch("/api/flights/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_AIR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_AIR"))
                .andExpect(jsonPath("$.allowedNextStatuses[0]").value("LANDED"))
                .andExpect(jsonPath("$.allowedNextStatuses.length()").value(1));
    }

    @Test
    void reportsNoAllowedNextStatusesOnceAFlightIsTerminal() throws Exception {
        Long id = givenFlight("WEB120", FlightStatus.IN_AIR);

        mockMvc.perform(patch("/api/flights/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LANDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LANDED"))
                .andExpect(jsonPath("$.allowedNextStatuses.length()").value(0));
    }

    private Long givenFlight(String flightNumber, FlightStatus status) {
        LocalDateTime departure = LocalDateTime.now().plusDays(1);
        Flight flight = new Flight(flightNumber, "JFK", "LHR", departure, departure.plusHours(3));
        flight.setStatus(status);
        return flightRepository.saveAndFlush(flight).getId();
    }
}
```

Why `@SpringBootTest` rather than `@WebMvcTest`: a slice needs a mocked `FlightService`, so the error tests in Task 4 would assert that a stubbed exception is mapped correctly while staying blind to the service failing to throw. This runs the real stack against H2. `@Transactional` rolls each test back, and the `WEB*` flight numbers cannot collide with the `data.sql` seed rows.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=FlightStatusControllerTest`
Expected: the test compiles (it references only classes that already exist) and all three tests fail with `Status expected:<200> but was:<404>`, because no handler is mapped to `PATCH /api/flights/{id}/status`. That 404 is the correct RED. If instead the Spring context fails to load, that is a setup error — fix it before continuing.

- [ ] **Step 3: Write the minimal implementation**

Create `src/main/java/com/flightcontrol/dto/StatusTransitionRequest.java`:

```java
package com.flightcontrol.dto;

import com.flightcontrol.domain.FlightStatus;
import jakarta.validation.constraints.NotNull;

public record StatusTransitionRequest(@NotNull(message = "must not be null") FlightStatus status) {
}
```

Modify `src/main/java/com/flightcontrol/dto/FlightResponse.java` — add the `allowedNextStatuses` component and populate it in `from`. The existing file becomes:

```java
package com.flightcontrol.dto;

import com.flightcontrol.domain.Flight;
import com.flightcontrol.domain.FlightStatus;

import java.time.LocalDateTime;
import java.util.List;

public record FlightResponse(
        Long id,
        String flightNumber,
        String origin,
        String destination,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        FlightStatus status,
        List<FlightStatus> allowedNextStatuses,
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
                List.copyOf(flight.getStatus().allowedNextStatuses()),
                flight.getCreatedAt(),
                flight.getUpdatedAt());
    }
}
```

Two things to know:

- `List.copyOf` over an `EnumSet` preserves declaration order, which is what `listsEveryAllowedNextStatusInDeclarationOrder` pins.
- Story 2's `create` response now carries the field too. That is intended — Story 3 requires flight responses to expose the transitions. Story 2's tests call `FlightResponse.from` and never construct the record directly, so the added component does not break them. If `mvn test` reports a constructor-arity error in a Story 2 test, stop and re-read that test rather than changing the record.

Create `src/main/java/com/flightcontrol/controller/FlightStatusController.java`:

```java
package com.flightcontrol.controller;

import com.flightcontrol.dto.FlightResponse;
import com.flightcontrol.dto.StatusTransitionRequest;
import com.flightcontrol.service.FlightService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/flights")
public class FlightStatusController {

    private final FlightService flightService;

    public FlightStatusController(FlightService flightService) {
        this.flightService = flightService;
    }

    @PatchMapping("/{id}/status")
    public FlightResponse transitionStatus(@PathVariable Long id,
                                           @Valid @RequestBody StatusTransitionRequest request) {
        return flightService.transitionStatus(id, request.status());
    }
}
```

The service already returns the DTO, matching `create`, so the controller maps nothing.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=FlightStatusControllerTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Run the whole suite**

Run: `mvn test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/flightcontrol/dto src/main/java/com/flightcontrol/controller src/test/java/com/flightcontrol/controller
git commit -m "Add PATCH flight status endpoint with allowedNextStatuses (Story 3)"
```

---

### Task 4: One error contract for 404, 409 and 400

**Files:**
- Create: `src/main/java/com/flightcontrol/exception/ApiErrorResponse.java`
- Create: `src/main/java/com/flightcontrol/exception/GlobalExceptionHandler.java`
- Test: `src/test/java/com/flightcontrol/controller/FlightStatusControllerTest.java` (created in Task 3 — add to it)

**Interfaces:**
- Consumes: `FlightNotFoundException`, `IllegalStatusTransitionException` (Task 2); the endpoint from Task 3.
- Produces: `ApiErrorResponse(LocalDateTime timestamp, int status, String error, String message, Map<String, String> fieldErrors)` with `@JsonInclude(NON_NULL)`, plus the advice that later stories extend rather than replace.

Story 2's `BusinessRuleViolationException` and `DuplicateFlightNumberException` stay **unmapped** — no endpoint can raise them yet, and handlers for an unreachable path could not be tested. Whoever adds the create endpoint adds their handlers here.

- [ ] **Step 1: Write the failing tests**

Append these tests to `FlightStatusControllerTest` (keep the three from Task 3 and the `givenFlight` helper):

```java
    @Test
    void returns404WithTheErrorContractWhenTheFlightDoesNotExist() throws Exception {
        mockMvc.perform(patch("/api/flights/{id}/status", 9_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DEPARTED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("FlightNotFound"))
                .andExpect(jsonPath("$.message").value("Flight 9999 not found"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void returns409NamingTheCurrentStatusAndAllowedTransitions() throws Exception {
        Long id = givenFlight("WEB200", FlightStatus.SCHEDULED);

        mockMvc.perform(patch("/api/flights/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LANDED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("IllegalStatusTransition"))
                .andExpect(jsonPath("$.message")
                        .value("Flight WEB200 is SCHEDULED; allowed transitions: DELAYED, DEPARTED, CANCELLED"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void returns409WhenTheFlightIsInATerminalStatus() throws Exception {
        Long id = givenFlight("WEB210", FlightStatus.CANCELLED);

        mockMvc.perform(patch("/api/flights/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SCHEDULED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IllegalStatusTransition"))
                .andExpect(jsonPath("$.message")
                        .value("Flight WEB210 is CANCELLED; CANCELLED is terminal, no transitions are allowed"));
    }

    @Test
    void returns400WhenTheStatusIsMissing() throws Exception {
        Long id = givenFlight("WEB300", FlightStatus.SCHEDULED);

        mockMvc.perform(patch("/api/flights/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("ValidationFailed"))
                .andExpect(jsonPath("$.fieldErrors.status").value("must not be null"));
    }

    @Test
    void returns400ListingTheKnownStatusesWhenTheStatusIsNotARealOne() throws Exception {
        Long id = givenFlight("WEB310", FlightStatus.SCHEDULED);

        mockMvc.perform(patch("/api/flights/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BOARDING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("ValidationFailed"))
                .andExpect(jsonPath("$.fieldErrors.status")
                        .value("must be one of: SCHEDULED, DELAYED, DEPARTED, IN_AIR, LANDED, CANCELLED"));
    }

    @Test
    void leavesTheFlightUntouchedWhenATransitionIsRejected() throws Exception {
        Long id = givenFlight("WEB400", FlightStatus.IN_AIR);

        mockMvc.perform(patch("/api/flights/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SCHEDULED\"}"))
                .andExpect(status().isConflict());

        org.assertj.core.api.Assertions.assertThat(
                        flightRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(FlightStatus.IN_AIR);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=FlightStatusControllerTest`
Expected: FAIL. Without the advice, the 404/409 cases surface as 500 and the 400 cases have no body — e.g. `Status expected:<409> but was:<500>` and `No value at JSON path "$.error"`.

- [ ] **Step 3: Write the minimal implementation**

Create `src/main/java/com/flightcontrol/exception/ApiErrorResponse.java`:

```java
package com.flightcontrol.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        Map<String, String> fieldErrors) {

    public static ApiErrorResponse of(HttpStatus status, String error, String message) {
        return new ApiErrorResponse(LocalDateTime.now(), status.value(), error, message, null);
    }

    public static ApiErrorResponse validationFailed(String message, Map<String, String> fieldErrors) {
        return new ApiErrorResponse(LocalDateTime.now(), HttpStatus.BAD_REQUEST.value(),
                "ValidationFailed", message, fieldErrors);
    }
}
```

Create `src/main/java/com/flightcontrol/exception/GlobalExceptionHandler.java`:

```java
package com.flightcontrol.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(FlightNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleFlightNotFound(FlightNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of(HttpStatus.NOT_FOUND, "FlightNotFound", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStatusTransitionException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalTransition(IllegalStatusTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(HttpStatus.CONFLICT, "IllegalStatusTransition", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.validationFailed("Request validation failed", fieldErrors));
    }

    /**
     * An unknown enum name never reaches Bean Validation - Jackson fails to bind it first.
     * Unwrapping it here keeps malformed input on the same JSON shape as every other error.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        if (ex.getCause() instanceof InvalidFormatException cause && cause.getTargetType().isEnum()) {
            String field = cause.getPath().isEmpty()
                    ? "body"
                    : cause.getPath().get(cause.getPath().size() - 1).getFieldName();
            String allowed = Arrays.stream(cause.getTargetType().getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            fieldErrors.put(field, "must be one of: " + allowed);
        }
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.validationFailed("Request body is not readable",
                        fieldErrors.isEmpty() ? null : fieldErrors));
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -Dtest=FlightStatusControllerTest`
Expected: PASS, 9 tests.

- [ ] **Step 5: Run the whole suite and the full build**

Run: `mvn clean verify`
Expected: BUILD SUCCESS, no test failures, and no new warnings in the output.

- [ ] **Step 6: Verify the endpoint against the running app**

```bash
mvn spring-boot:run &
sleep 15
# Seeded BA117 is SCHEDULED; find its id first:
curl -s "http://localhost:8080/h2-console" -o /dev/null -w "app up: %{http_code}\n"
curl -s -X PATCH http://localhost:8080/api/flights/1/status \
  -H 'Content-Type: application/json' -d '{"status":"DEPARTED"}'
echo
curl -s -X PATCH http://localhost:8080/api/flights/1/status \
  -H 'Content-Type: application/json' -d '{"status":"LANDED"}'
echo
pkill -f spring-boot:run
```

Expected: the first call returns 200 with `"allowedNextStatuses":["IN_AIR"]`; the second returns the 409 body naming DEPARTED and IN_AIR. Seed row 1 is `BA117` (SCHEDULED).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/flightcontrol/exception src/test/java/com/flightcontrol/controller
git commit -m "Add global error contract for 404, 409 and 400 (Story 3)"
```

---

## Acceptance criteria mapping

| Story 3 criterion | Task |
|---|---|
| Allowed transitions as listed | 1 |
| Other transitions rejected with 409 naming current status + allowed set | 2 (message), 4 (status code + body) |
| CANCELLED and LANDED terminal regardless of target | 1 (empty set), 2 (exhaustive service tests) |
| Response exposes transitionable statuses | 3 |
| Transition table encoded once, read by both the endpoint and responses | 1, consumed by 2 and 3 |
| Status change is a separate endpoint from detail edit | 3 |

## Definition of done

- `mvn clean verify` is green with no new warnings.
- Every pre-existing test still passes, Story 2's `FlightServiceTest` included. Story 3 adds 76 tests: 49 (Task 1) + 18 (Task 2) + 9 (Tasks 3 and 4).
- No schema change, no new columns, no `@Version`.
- `FlightService.create` and its helpers are byte-for-byte unchanged.
- Nothing from Stories 4 or 5 has been implemented, and no create/update/delete/search endpoint exists.
