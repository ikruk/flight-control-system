# Flight Control System

Full-stack flight management system: airline staff create, update, delete, search
and transition flights through a status lifecycle. Business rules are enforced on
the backend and reflected in the UI — the frontend never re-implements a rule.

## Stack

- Java 21, Spring Boot 3, Maven
- Spring Data JPA over H2 (in-memory, dev only)
- Schema is explicit: `src/main/resources/schema.sql` + `data.sql`.
  `spring.jpa.hibernate.ddl-auto=none` — never turn DDL auto-generation back on.
- JUnit 5, MockMvc, AssertJ
- Frontend: vanilla JS + fetch, no framework, no build step
- springdoc-openapi for Swagger UI at `/swagger-ui.html`

## Commands

```bash
mvn test                 # unit + controller tests
mvn spring-boot:run      # http://localhost:8080
mvn clean verify         # full build, run before declaring anything done
```

H2 console: `/h2-console` (JDBC URL `jdbc:h2:mem:flights`).

## Layering

```
controller/   HTTP only: map request → DTO, call service, map result → response
service/      all business rules; the only layer that decides what is legal
repository/   Spring Data interfaces, no logic
domain/       entities and the FlightStatus enum
dto/          request/response records, separate from entities
exception/    domain exceptions + one @RestControllerAdvice
```

Rules:

- Entities never cross the controller boundary — map to DTOs.
- Controllers contain no `if` on business state. If a controller decides whether
  something is allowed, it is in the wrong layer.
- Constructor injection only. No `@Autowired` on fields.
- No `Optional` as a method parameter; return `Optional` from repositories and
  resolve it in the service.

## Domain rules (source of truth lives in code, not here)

- `FlightStatus`: SCHEDULED, DELAYED, DEPARTED, IN_AIR, LANDED, CANCELLED.
  New flights are SCHEDULED.
- The allowed-transition table is encoded once, on the enum. Both the transition
  endpoint and the `allowedNextStatuses` field in responses read from it. If you
  find yourself writing the transition rules a second time anywhere, stop.
- Status changes go through their own narrow endpoint, separate from editing
  flight details — different rules, different operation.
- Cross-field and uniqueness rules (time ordering, origin ≠ destination, unique
  flight number) live in the service, because they need repository access.
  Field-shape rules (required, format, length) are Bean Validation annotations
  on the request DTO.
- `flight_number` uniqueness is enforced by a database constraint as well as in
  the service. Both, not either.

## Error contract

One `@RestControllerAdvice` translates everything into the same JSON shape:

```json
{
  "timestamp": "...",
  "status": 409,
  "error": "IllegalStatusTransition",
  "message": "...",
  "fieldErrors": { "departureTime": "must be in the future" }
}
```

Status codes carry meaning and tests assert on them:

- `400` — invalid input (shape or business rule on the data itself)
- `404` — flight does not exist
- `409` — the request is valid but the flight's current state forbids it
  (illegal transition, edit/delete of a non-editable flight, duplicate number)

Never return a stack trace or a bare 500 for a case we can name.

## Testing

- Every business rule and every legal/illegal transition has a service-level unit
  test. Illegal transitions are tested exhaustively, not by sampling.
- Controller tests use MockMvc and assert the status code and the error body, not
  only the happy path.
- Test names read as sentences:
  `rejectsTransitionFromLandedToAnyStatus`, `rejectsDepartureTimeInThePast`.
- Tests must not depend on execution order or on rows seeded by `data.sql`;
  build the data the test needs.
- Seed data in `data.sql` uses timestamps relative to now, never hardcoded dates.

## Frontend conventions

- `allowedNextStatuses`, `editable` and `deletable` come from the API. The UI
  renders buttons from those fields and never decides them itself.
- Filtering, sorting and pagination happen server-side; the table renders what
  the API returned.
- Field-level errors from the API's `fieldErrors` are shown next to their input,
  not as a single generic message.
- Destructive actions (delete, cancel) confirm before the request fires.

## Out of scope

No authentication, no user roles, no multi-tenancy, no production database
config. If a task seems to need one of these, it is a misread of the task — ask.
