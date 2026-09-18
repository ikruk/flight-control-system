# Story 2 — Business Rule Validation on Create/Update: Design

Date: 2026-09-18
Status: approved in brainstorming, pending written-spec review
Source story: `docs/story.md`, Story 2

## Goal

Reject invalid flight data on create and update, and report every failure in the
structured error shape defined in `CLAUDE.md`, naming the offending field. No
failure we can name may surface as a 500 or a stack trace.

## Acceptance criteria

| Rule | Status | Field reported |
|---|---|---|
| `departureTime` not strictly in the future | 400 | `departureTime` |
| `arrivalTime` not strictly after `departureTime` | 400 | `arrivalTime` |
| `origin` equals `destination` | 400 | `destination` |
| `flightNumber` already exists, compared case-insensitively | 409 | `flightNumber` |
| Field-shape failure (missing, wrong format) | 400 | the field |

## Scope

In scope:

- `POST /api/flights` and `PUT /api/flights/{id}`
- Request/response/error DTOs
- `FlightService` with all business rules
- The single `@RestControllerAdvice`
- 404 for `PUT` on an unknown id (the advice must be able to name it)
- One schema constraint supporting case-insensitive uniqueness

Out of scope (later stories): GET/DELETE endpoints, status transitions and
`allowedNextStatuses` (Story 3), edit/delete status guardrails and
`editable`/`deletable` (Story 4), search/pagination (Story 5), Swagger
annotations (Story 6), frontend (Stories 7–8). `FlightStatus` is not modified.

## Decisions

1. **Case-insensitive uniqueness by normalization.** The service uppercases
   `flightNumber` before checking and saving. The existing
   `UNIQUE (flight_number)` constraint then behaves case-insensitively. A new
   `CHECK (flight_number = UPPER(flight_number))` makes the database itself
   reject non-canonical rows, satisfying "both, not either".
2. **IATA-shaped field formats.** Input is accepted in any case and normalized
   to uppercase by the service; `origin`/`destination` are normalized the same
   way, so `lhr` and `LHR` are the same airport.
3. **Aggregate data-rule errors.** All three 400 business rules are evaluated
   and reported together. Uniqueness (409) is checked only after they pass, so a
   response never mixes 400 and 409.
4. **Rules inline in `FlightService`** (no separate validator component, no
   custom class-level Bean Validation constraints). A `Clock` is injected so
   "now" is deterministic in tests.

## Components

New:

```
dto/FlightRequest.java         record: flightNumber, origin, destination, departureTime, arrivalTime
dto/FlightResponse.java        record: id, flightNumber, origin, destination, departureTime,
                               arrivalTime, status, createdAt, updatedAt; static from(Flight)
dto/ApiError.java              record: timestamp, status, error, message, fieldErrors
service/FlightService.java     create(FlightRequest), update(Long, FlightRequest)
controller/FlightController.java
exception/BusinessRuleViolationException.java   carries Map<String,String> fieldErrors
exception/DuplicateFlightNumberException.java
exception/FlightNotFoundException.java
exception/GlobalExceptionHandler.java
config/ClockConfig.java        @Bean Clock (system default zone)
```

Changed:

- `pom.xml` — add `spring-boot-starter-validation`.
- `FlightRepository` — derived queries `existsByFlightNumberIgnoreCase(String)`
  and `existsByFlightNumberIgnoreCaseAndIdNot(String, Long)`.
- `schema.sql` — add
  `CONSTRAINT ck_flights_flight_number_upper CHECK (flight_number = UPPER(flight_number))`.
  Seed rows are already uppercase. No route or time CHECK constraints are added.
- `application.properties` — `server.error.include-stacktrace=never`,
  `server.error.include-message=never`.

## Field-shape rules (`FlightRequest`)

| Field | Constraints |
|---|---|
| `flightNumber` | `@NotNull`, `@Pattern(^[A-Za-z0-9]{2}\d{1,4}[A-Za-z]?$)` |
| `origin`, `destination` | `@NotNull`, `@Pattern(^[A-Za-z]{3}$)` |
| `departureTime`, `arrivalTime` | `@NotNull`, type `LocalDateTime` |

`@NotNull` + `@Pattern` (rather than `@NotBlank` + `@Pattern`) yields exactly one
message per field: `@Pattern` passes on null and fails on `""`. The patterns fit
the schema columns (`VARCHAR(10)`, `VARCHAR(3)`), so a valid request can never
overflow a column. The past-departure rule is deliberately not `@Future` on the
DTO; it lives in the service with the injected `Clock`.

## Data flow

Create:

1. Controller: `@Valid @RequestBody FlightRequest` → `service.create` →
   `201 Created`, `Location: /api/flights/{id}`, body `FlightResponse`. The
   controller contains no conditional on business state.
2. Service normalizes `flightNumber`, `origin`, `destination` with
   `toUpperCase(Locale.ROOT)`. No trim is needed; the patterns forbid whitespace.
3. Service collects data-rule errors, with `now = LocalDateTime.now(clock)`:
   - `!departureTime.isAfter(now)` → `departureTime: "must be in the future"`
   - `!arrivalTime.isAfter(departureTime)` → `arrivalTime: "must be after departure time"`
   - `origin.equals(destination)` → `destination: "must differ from origin"`
   If any, throw `BusinessRuleViolationException` and stop.
4. `existsByFlightNumberIgnoreCase` → `DuplicateFlightNumberException`.
5. Save and map. Status is the entity default `SCHEDULED`; the request has no
   status field.

Update: `findById` → `FlightNotFoundException` if absent → steps 2–4, with
uniqueness via `existsByFlightNumberIgnoreCaseAndIdNot(number, id)` so a flight
may keep its own number → set detail fields only → `200 OK`. Status is never
touched and no status guardrail applies (Story 4).

Both service methods are `@Transactional`.

## Error handling

`GlobalExceptionHandler` is a `@RestControllerAdvice` extending
`ResponseEntityExceptionHandler`, overriding `handleExceptionInternal` so that
Spring's own errors (405, 415, unreadable body, …) use the same `ApiError` shape.

| Exception | Status | `error` | `fieldErrors` |
|---|---|---|---|
| `MethodArgumentNotValidException` | 400 | `ValidationFailed` | one entry per invalid field |
| `BusinessRuleViolationException` | 400 | `BusinessRuleViolation` | from the exception (1–3 entries) |
| `HttpMessageNotReadableException` | 400 | `MalformedRequest` | if caused by Jackson `InvalidFormatException`, the field from the JSON path with `"invalid format"`; otherwise empty |
| `MethodArgumentTypeMismatchException` | 400 | `MalformedRequest` | the parameter name (e.g. `id`) |
| `FlightNotFoundException` | 404 | `FlightNotFound` | empty |
| `DuplicateFlightNumberException` | 409 | `DuplicateFlightNumber` | `flightNumber: "already exists"` |
| `DataIntegrityViolationException` naming `UK_FLIGHTS_FLIGHT_NUMBER` | 409 | `DuplicateFlightNumber` | same; backstop for two concurrent requests passing the service check |
| any other `Exception` | 500 | `InternalError` | empty; fixed message `"unexpected error"`, cause logged only |

- `fieldErrors` is always present, `{}` when there are none.
- `timestamp` is `Instant.now(clock)`.
- A `DataIntegrityViolationException` not naming the unique key falls through to
  the generic 500. It is unreachable through the API while the DTO patterns match
  the column sizes, so no named case is invented for it.

## Testing

TDD, red first. Test names read as sentences. No test depends on `data.sql` rows
or execution order.

`FlightServiceTest` — Mockito repository, fixed `Clock`:

- `rejectsDepartureTimeInThePast`, `rejectsDepartureTimeEqualToNow`
- `rejectsArrivalTimeBeforeDeparture`, `rejectsArrivalTimeEqualToDeparture`
- `rejectsSameOriginAndDestination`, `rejectsSameOriginAndDestinationDifferingOnlyByCase`
- `reportsEveryViolatedRuleInOneException`
- `rejectsDuplicateFlightNumberIgnoringCase`
- `skipsUniquenessCheckWhenDataRulesFail`
- `storesFlightNumberAndAirportCodesInUpperCase`, `createsFlightAsScheduled`
- Update: `rejectsUpdateOfUnknownFlight`, `allowsUpdateKeepingOwnFlightNumber`,
  `rejectsUpdateToAnotherFlightsNumber`, one re-validation test per data rule,
  `updateLeavesStatusUnchanged`

`FlightControllerTest` — `@WebMvcTest`, `@MockitoBean FlightService`:

- 201 with `Location` and body on create; 200 on update
- 400 for each shape rule on each field, asserting `error` and the exact
  `fieldErrors` key
- 400 business rule, 409 duplicate, 404 not found — status and full error body
- malformed JSON → 400; bad date string → 400 with `departureTime` key;
  `PUT /api/flights/abc` → 400
- unexpected `RuntimeException` from the service → 500 whose body contains no
  `trace` and no exception class name

`FlightRepositoryTest` (extended): `existsByFlightNumberIgnoreCase` matches
across case; `...AndIdNot` excludes the flight itself.

`FlightSchemaConstraintsTest` (extended): `databaseRejectsLowercaseFlightNumber`.

`FlightApiIntegrationTest` — one `@SpringBootTest` with MockMvc over the real
stack: POST `XY123`, then POST `xy123` → 409 with the contract body.

Done means `mvn clean verify` is green.
