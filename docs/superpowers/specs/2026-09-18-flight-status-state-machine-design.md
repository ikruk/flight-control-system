# Flight status state machine — design

**Story:** 3 (`docs/story.md`)
**Date:** 2026-09-18
**Status:** approved, ready for an implementation plan
**Revised:** 2026-09-18, after Story 2's service-level work landed (`0cd553a`). See "Baseline".

## Baseline

Story 2 shipped the service layer but no HTTP layer. What already exists:

- `FlightService(FlightRepository, Clock)` with `create(FlightRequest)` returning
  a **`FlightResponse`** — the service maps to the DTO, the controller will not.
- `FlightResponse` without `allowedNextStatuses`; `FlightRequest`; `ClockConfig`.
- `BusinessRuleViolationException`, `DuplicateFlightNumberException`, both unmapped.
- `FlightServiceTest`, Mockito-based against a mocked repository.
- `spring-boot-starter-validation` in `pom.xml`.
- `schema.sql` additionally enforces `ck_flights_flight_number_upper`.

Still absent: any controller, `GlobalExceptionHandler`, `ApiErrorResponse`,
`FlightNotFoundException`.

Three decisions follow from that baseline:

1. `transitionStatus` returns `FlightResponse`, matching `create`. This departs
   from `CLAUDE.md`'s "controllers map result to response", but the code already
   departed from it and one convention per class beats one convention per doc.
2. Transition tests go in a new `FlightTransitionServiceTest` using `@DataJpaTest`
   with a real repository, as this spec's Testing section requires. Story 2's
   mock-based `FlightServiceTest` is left alone.
3. The advice maps Story 3's exceptions only. Story 2's two exceptions cannot be
   reached over HTTP until a create endpoint exists, so mapping them now would be
   untested handlers for an unreachable path.

## Goal

Flights move between statuses only through allowed transitions. The transition
table is written once, on `FlightStatus`, and both the transition endpoint and
the `allowedNextStatuses` field in flight responses read from it.

## Scope

In scope:

- The transition table on `FlightStatus`.
- `FlightService.transitionStatus`.
- `PATCH /api/flights/{id}/status`.
- `FlightResponse`, including `allowedNextStatuses`.
- The first `@RestControllerAdvice`, handling 404, 409 and 400.

Out of scope, and why:

- Create, update, delete and search endpoints — Stories 2, 4 and 5.
- `editable` / `deletable` on the response — Story 4 owns those rules. Adding
  the fields now means guessing them.
- Optimistic locking. Two agents transitioning the same flight concurrently can
  have one overwrite the other. No story raises concurrency, and a `@Version`
  column would add a conflict path to every later story. Decided out
  deliberately, not overlooked.

## Transition table

| From | Allowed targets |
|---|---|
| SCHEDULED | DELAYED, DEPARTED, CANCELLED |
| DELAYED | DEPARTED, CANCELLED |
| DEPARTED | IN_AIR |
| IN_AIR | LANDED |
| LANDED | none (terminal) |
| CANCELLED | none (terminal) |

Any pair not in this table is rejected, self-transitions included:
`SCHEDULED → SCHEDULED` is a 409, not an idempotent no-op. It is not in the
table, so it is illegal like any other unlisted pair — one rule, no special
case. Story 8's UI renders a button per allowed transition, so it never offers
the current status anyway.

## Components

```
domain/FlightStatus                  MODIFY  allowedNextStatuses(), canTransitionTo(), isTerminal()
service/FlightService                MODIFY  + transitionStatus(id, target) -> FlightResponse
dto/FlightResponse                   MODIFY  + allowedNextStatuses
dto/StatusTransitionRequest          CREATE  record { @NotNull FlightStatus status }
controller/FlightStatusController    CREATE  PATCH /api/flights/{id}/status
exception/FlightNotFoundException          CREATE  -> 404
exception/IllegalStatusTransitionException CREATE  -> 409
exception/ApiErrorResponse                 CREATE  the JSON error shape
exception/GlobalExceptionHandler           CREATE  @RestControllerAdvice
```

No dependency changes — `spring-boot-starter-validation` arrived with Story 2.

Adding `allowedNextStatuses` to `FlightResponse` also gives Story 2's `create`
response the field, which is what Story 3's criterion asks for ("the API response
for a flight includes which statuses it can currently transition to").

### FlightStatus

The table is an exhaustive `switch` with no `default`:

```java
public Set<FlightStatus> allowedNextStatuses() {
    return switch (this) {
        case SCHEDULED -> unmodifiableSet(EnumSet.of(DELAYED, DEPARTED, CANCELLED));
        case DELAYED   -> unmodifiableSet(EnumSet.of(DEPARTED, CANCELLED));
        case DEPARTED  -> unmodifiableSet(EnumSet.of(IN_AIR));
        case IN_AIR    -> unmodifiableSet(EnumSet.of(LANDED));
        case LANDED, CANCELLED -> unmodifiableSet(EnumSet.noneOf(FlightStatus.class));
    };
}
```

`unmodifiableSet` is `java.util.Collections.unmodifiableSet`, statically
imported. Every branch returns an `EnumSet` so iteration order is uniform.

`canTransitionTo(target)` and `isTerminal()` are derived from it —
`isTerminal()` is `allowedNextStatuses().isEmpty()`, not a separate flag that
could drift.

Two properties this buys:

- A seventh status fails to compile until someone declares its transitions. The
  compiler enforces the single-source-of-truth rule, not a reviewer.
- `EnumSet` iterates in declaration order, so `allowedNextStatuses` reaches the
  UI in a stable order rather than a hash order that varies per JVM run.

Rejected alternatives: a static `EnumMap` (a missing key degrades to empty at
runtime instead of failing the build) and an injectable `TransitionPolicy`
component (swappability no story asks for, and it separates the rules from the
type they describe).

### FlightService

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

A new method on the existing `FlightService`, alongside `create`. The only layer
that decides legality. The dirty-checked entity flushes on commit, and
`@PreUpdate` refreshes `updated_at`.

`IllegalStatusTransitionException` takes the flight only. The approved message
text never names the target status, so a target parameter would be unused.

### Controller

```
PATCH /api/flights/{id}/status
{ "status": "DEPARTED" }

200 OK
{
  "id": 42,
  "flightNumber": "DL44",
  "origin": "JFK",
  "destination": "LAX",
  "departureTime": "...",
  "arrivalTime": "...",
  "status": "DEPARTED",
  "allowedNextStatuses": ["IN_AIR"],
  "createdAt": "...",
  "updatedAt": "..."
}
```

The controller binds `@Valid @RequestBody StatusTransitionRequest` and returns
what the service hands back. No branching on business state, and no mapping —
`FlightService` already returns the DTO, as it does for `create`.
`allowedNextStatuses` is computed from the new status inside `FlightResponse.from`,
so the response always says where the flight can go next.

## Error contract

One `@RestControllerAdvice` produces the shape from `CLAUDE.md`, with
`@JsonInclude(NON_NULL)` so `fieldErrors` is absent rather than null.

| Case | Status | `error` |
|---|---|---|
| Unknown flight id | 404 | `FlightNotFound` |
| Transition not in the table | 409 | `IllegalStatusTransition` |
| Transition from a terminal status | 409 | `IllegalStatusTransition` |
| `status` missing or not a known name | 400 | `ValidationFailed` |

Story 2's `BusinessRuleViolationException` and `DuplicateFlightNumberException`
stay unmapped for now — no endpoint can raise them yet. The advice is the place
they get mapped when a create endpoint lands.

Messages name the current status and the allowed set, so a client never
re-derives the rules:

- `Flight BA117 is SCHEDULED; allowed transitions: DELAYED, DEPARTED, CANCELLED`
- `Flight AA100 is LANDED; LANDED is terminal, no transitions are allowed`

An unknown enum name arrives as `HttpMessageNotReadableException`, not as a
validation failure. The handler unwraps `InvalidFormatException` and reports
`fieldErrors.status = "must be one of: SCHEDULED, DELAYED, DEPARTED, IN_AIR,
LANDED, CANCELLED"`, so malformed input gets the same JSON shape as everything
else instead of Spring's empty-bodied 400.

## Testing

Written test-first, per `superpowers:test-driven-development`.

**`FlightStatusTest`** — all 36 `(from, to)` pairs, parameterized. The expected
legal set is written out literally in the test file, never read from
`allowedNextStatuses()`; deriving it from production code would assert only that
the code equals itself. Also: the exact allowed set per status, `isTerminal()`
for both terminal statuses, and self-transitions (covered by the 36-pair sweep).

**`FlightTransitionServiceTest`** — `@DataJpaTest` +
`@Import({FlightService.class, ClockConfig.class})`. Real service, real
repository, real H2, no mocks. A new class rather than additions to Story 2's
mock-based `FlightServiceTest`, which stays as it is. Each test builds its own
data and reads no seed rows.

- A legal transition persists and refreshes `updated_at`.
- An illegal transition throws, with the current status and allowed set in the message.
- Each terminal status rejects every target.
- An unknown id throws `FlightNotFoundException`.
- A rejected transition leaves the stored status unchanged.

**`FlightStatusControllerTest`** — `@SpringBootTest` + `@AutoConfigureMockMvc`,
against the real stack rather than a `@WebMvcTest` slice. A slice needs a mocked
service, which would make the 409 test assert that a stubbed exception is mapped
correctly while staying blind to the service failing to throw at all. The cost
is one Spring context.

- 200, with `allowedNextStatuses` in the body.
- 404 for an unknown id.
- 409 with the full error shape for an illegal transition.
- 400 for a null status.
- 400 for an unknown status name.

## Acceptance criteria mapping

| Story 3 criterion | Covered by |
|---|---|
| Allowed transitions as listed | `FlightStatus.allowedNextStatuses`, `FlightStatusTest` |
| Other transitions rejected with 409 naming current + allowed | `IllegalStatusTransitionException`, `GlobalExceptionHandler`, controller test |
| CANCELLED and LANDED terminal | empty allowed set, `isTerminal()`, service test |
| Response exposes transitionable statuses | `FlightResponse.allowedNextStatuses` |
| Table encoded once | exhaustive `switch`; both the endpoint and the response read it |
| Status change separate from detail edit | dedicated `PATCH .../status` endpoint |
