# Flight Control System — Technical Story

**Epic:** Flight Control System
**Owner:** Technical Lead / PO
**Summary:** Build a full-stack flight management system (Spring Boot + vanilla JS) that lets airline staff create, update, delete, search, and transition flights through a defined status lifecycle, with validation enforced consistently on the backend and reflected clearly in the UI.

---

## Story 1 — Flight domain model & data layer

**As a** backend engineer
**I want** a `Flight` entity and schema that captures all flight attributes and their status
**So that** every other story has a consistent, persisted source of truth to build against

**Acceptance Criteria**
- `Flight` has: id, flight_number (unique), origin, destination, departure_time, arrival_time, status, and audit timestamps (created_at, updated_at).
- Status is one of: SCHEDULED, DELAYED, DEPARTED, IN_AIR, LANDED, CANCELLED. Default on creation is SCHEDULED.
- Schema is defined declaratively (`schema.sql`) and seeded with representative sample flights across all statuses (`data.sql`) so the UI has data to render on first run.
- `flight_number` uniqueness is enforced at the database level, not just in application code.

**Technical Notes**
- H2 in-memory for dev; DDL auto-generation disabled in favor of `schema.sql`/`data.sql` so the schema is explicit and reviewable.
- Seed timestamps should be relative to "now" (not hardcoded dates) so the dataset doesn't go stale.

---

## Story 2 — Business rule validation on create/update

**As an** airline scheduler
**I want** the system to reject invalid flight data
**So that** the flight list never contains impossible or ambiguous schedules

**Acceptance Criteria**
- Creating a flight with `departure_time` in the past is rejected (400).
- `arrival_time` must be strictly after `departure_time` (400).
- `origin` and `destination` must differ (400).
- `flight_number` must be unique across all flights, case-insensitively (409 Conflict).
- All validation failures return a structured error response identifying which field(s) failed and why — not a generic 500 or stack trace.

**Technical Notes**
- Field-shape validation (required, format, length) via Bean Validation annotations on the request DTO.
- Cross-field/business rules (time ordering, uniqueness) live in the service layer, since they need repository access and aren't expressible as simple annotations.
- One global exception handler translates both validation failures and business-rule violations into a consistent JSON error shape.

---

## Story 3 — Flight status state machine

**As an** operations agent
**I want** flights to move through statuses only via allowed transitions
**So that** a flight can't skip states (e.g. go straight from SCHEDULED to LANDED) or resurrect from a terminal state

**Acceptance Criteria**
- Allowed transitions:
  - SCHEDULED → DELAYED, DEPARTED, CANCELLED
  - DELAYED → DEPARTED, CANCELLED
  - DEPARTED → IN_AIR
  - IN_AIR → LANDED
- Any other transition attempt is rejected (409 Conflict) with a message stating the current status and which transitions are actually allowed.
- CANCELLED and LANDED are terminal: any transition attempt from either is rejected, regardless of target.
- The API response for a flight includes which statuses it can currently transition to, so the frontend doesn't have to re-implement the rules to decide which buttons to show.

**Technical Notes**
- Encode the transition table once (e.g., as a method on the status enum) so it's the single source of truth for both the transition endpoint and the "allowed next statuses" exposed in responses.
- Status transitions are a separate, narrow endpoint from general update — this keeps "edit flight details" and "change flight status" as distinct operations with distinct rules (see Story 4).

---

## Story 4 — Update and delete guardrails

**As an** operations agent
**I want** edit and delete actions blocked once a flight is past the point where changes make sense
**So that** in-progress or completed flights can't be silently altered or removed

**Acceptance Criteria**
- `PUT` (full update of flight details) succeeds only when status is SCHEDULED or DELAYED; otherwise 409 Conflict with a clear reason.
- `DELETE` succeeds only when status is SCHEDULED; otherwise 409 Conflict with a clear reason.
- Update still re-validates all Story 2 business rules (times, route, uniqueness) — editability doesn't bypass data validity.
- Each flight response tells the frontend whether it's currently editable/deletable, so the UI can disable those actions without guessing.

---

## Story 5 — Search, filter, and pagination API

**As an** operations agent monitoring many flights
**I want** to search and filter the flight list
**So that** I can find a specific flight or a relevant subset quickly instead of scrolling a long table

**Acceptance Criteria**
- Flights can be filtered by any combination of: flight number (partial match), origin, destination, status.
- Results are paginated with configurable page size, and sortable (default: soonest departure first).
- The response includes total count and page metadata so the UI can render pagination controls without a separate count call.
- No filters applied returns all flights, paginated.

---

## Story 6 — API documentation

**As a** frontend engineer (or third-party integrator)
**I want** a live, accurate API reference
**So that** I can build against the API without reading backend source code

**Acceptance Criteria**
- Swagger UI is available at `/swagger-ui.html` and reflects the real request/response shapes, including validation constraints and possible error codes.
- Every endpoint has a human-readable summary of what it does and when it fails.

---

## Story 7 — Flight List page (frontend)

**As an** operations agent
**I want** a table of flights with search, filters, and pagination
**So that** flight list management is my default working view

**Acceptance Criteria**
- Table shows key columns (flight number, route, departure/arrival, status) with status rendered as a colored badge per status value.
- Search/filter controls call the backend filter API rather than filtering client-side.
- Pagination controls navigate pages using the API's page metadata.
- "Create flight" opens a modal with a form matching backend validation (required fields, format hints, inline error messages sourced from the API's structured error response).
- API calls show a loading indicator; success/failure show a toast notification.
- Clicking a row navigates to the Flight Detail page for that flight.

---

## Story 8 — Flight Detail page (frontend)

**As an** operations agent
**I want** a detail view with status controls and edit/delete actions
**So that** I can manage an individual flight's full lifecycle in one place

**Acceptance Criteria**
- Displays all flight fields, with status as a colored badge.
- Renders one button per currently-allowed status transition (driven by the API's `allowedNextStatuses`, not hardcoded frontend logic) — terminal-state flights show no transition buttons.
- Edit form is only available/enabled when the flight is editable per the API; same for delete.
- Delete and "Cancel flight" actions require confirmation before the API call fires.
- Form validation mirrors backend constraints; server-side validation errors are surfaced per-field, not as a generic failure message.
- Loading and toast feedback match the List page's patterns for consistency.

---

## Story 9 — Automated test coverage

**As a** technical lead
**I want** the business rules and state machine covered by automated tests
**So that** regressions in validation or transition logic are caught before release, not in production

**Acceptance Criteria**
- Unit tests cover the service layer: every business rule from Story 2 (past departure, bad ordering, same origin/destination, duplicate flight number) and every legal/illegal transition from Story 3.
- Controller tests (MockMvc) cover request validation, correct HTTP status codes per failure mode (400 vs 404 vs 409), and the happy path for each endpoint.
- Edit/delete guardrails (Story 4) are tested for both the allowed and blocked status cases.

---

## Out of scope (explicitly, for this story set)
- Authentication/authorization — no user roles or login are defined in the source brief.
- Multi-airline or multi-tenant data isolation.
- Production database configuration (Postgres/MySQL) — H2 is dev-only per the brief.
