# Flight status state machine — design

**Story:** 3 (`docs/story.md`)
**Date:** 2026-09-18
**Status:** approved, ready for an implementation plan

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
domain/FlightStatus                  allowedNextStatuses(), canTransitionTo(), isTerminal()
service/FlightService                transitionStatus(id, target) -> Flight
dto/StatusTransitionRequest          record { @NotNull FlightStatus status }
dto/FlightResponse                   record + static from(Flight)
controller/FlightStatusController    PATCH /api/flights/{id}/status
exception/FlightNotFoundException          -> 404
exception/IllegalStatusTransitionException -> 409
exception/ApiErrorResponse                 the JSON error shape
exception/GlobalExceptionHandler           @RestControllerAdvice
```

`spring-boot-starter-validation` is the only new dependency.

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
public Flight transitionStatus(Long id, FlightStatus target) {
    Flight flight = repository.findById(id).orElseThrow(() -> new FlightNotFoundException(id));
    if (!flight.getStatus().canTransitionTo(target)) {
        throw new IllegalStatusTransitionException(flight, target);
    }
    flight.setStatus(target);
    return flight;
}
```

Constructor injection. The only layer that decides legality. The dirty-checked
entity flushes on commit, and `@PreUpdate` refreshes `updated_at`.

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

The controller binds `@Valid @RequestBody StatusTransitionRequest`, calls the
service, and maps the result with `FlightResponse.from(...)`. No branching on
business state. `allowedNextStatuses` is computed from the new status, so the
response always says where the flight can go next.

## Error contract

One `@RestControllerAdvice` produces the shape from `CLAUDE.md`, with
`@JsonInclude(NON_NULL)` so `fieldErrors` is absent rather than null.

| Case | Status | `error` |
|---|---|---|
| Unknown flight id | 404 | `FlightNotFound` |
| Transition not in the table | 409 | `IllegalStatusTransition` |
| Transition from a terminal status | 409 | `IllegalStatusTransition` |
| `status` missing or not a known name | 400 | `ValidationFailed` |

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

**`FlightServiceTest`** — `@DataJpaTest` + `@Import(FlightService.class)`. Real
service, real repository, real H2, no mocks. Each test builds its own data and
reads no seed rows.

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
