# Maintenance Management System

## Overview

A secure Java REST API for registering assets, raising maintenance work orders, assigning technicians, recording labor, and tracking completion.

## Features

- Admin, Technician, and Requester role-based access
- Asset registry with active, under-maintenance, and retired states
- Work orders with generated references and validated priorities
- Transactional technician assignment and asset-state updates
- Enforced work-order lifecycle and technician ownership
- Maintenance labor logs with requester-safe visibility
- Administrative maintenance dashboard

## Technology Stack

Java 21, Javalin 7.2.3, JDBC, H2 2.5.250, JUnit 6.1.3, Maven, and Docker. The JDBC layer can be adapted to PostgreSQL for production.

## Installation and Running

Install Java 21+ and Maven 3.9.16+, then run:

```bash
mvn clean package
java -jar target/maintenance-management-system-1.0.0.jar
```

The API listens on `http://localhost:8080` by default.

## Environment and Database

Copy `.env.example` values into your runtime environment. `DB_URL` defaults to file-backed H2. The idempotent schema in `src/main/resources/db/migration.sql` runs automatically, and seed data is added only when the users table is empty.

## Default Development Accounts

| Username | Role | Password |
| --- | --- | --- |
| `admin` | Admin | `ChangeMe123!` |
| `technician` | Technician | `ChangeMe123!` |
| `requester` | Requester | `ChangeMe123!` |

Change or remove these accounts outside local development.

## API Endpoints

| Method | Endpoint | Access |
| --- | --- | --- |
| POST | `/api/auth/login` | Public |
| GET | `/api/assets` | Authenticated |
| POST | `/api/assets` | Admin |
| GET | `/api/work-orders` | Role-scoped |
| POST | `/api/work-orders` | Admin, Requester |
| PATCH | `/api/work-orders/{id}/assignment` | Admin |
| PATCH | `/api/work-orders/{id}/status` | Admin, assigned Technician |
| GET/POST | `/api/work-orders/{id}/logs` | Role-scoped / Admin, assigned Technician |
| GET | `/api/dashboard` | Admin |

Send the login token as `Authorization: Bearer <token>`. Responses use `data` or structured `error` envelopes.

## Testing

Run `mvn verify`. The suite covers work-order creation, retired-asset protection, transactional assignment, asset state synchronization, technician ownership, labor logging, and lifecycle completion.

## Project Structure

- `Core.java` — database/authentication infrastructure and transactional maintenance rules
- `Main.java` — HTTP API, authorization boundaries, ownership filters, and development data
- `src/main/resources/db/migration.sql` — relational schema
- `src/test` — service-level business-rule tests

## Security Notes

Passwords use PBKDF2-HMAC-SHA256 with per-password salts and 210,000 iterations. Random bearer tokens are stored only as SHA-256 digests and expire after two hours. The API enforces roles, requester visibility, assigned-technician ownership, parameterized SQL, transaction boundaries, and unique asset/work-order references. Deploy behind HTTPS, rotate seed credentials, use managed PostgreSQL, and add centralized secrets and immutable audit retention for production.

## Known Limitations

Preventive schedules, spare-parts inventory, sensor ingestion, email/SMS notifications, file attachments, and external asset-management integrations are outside this reference implementation.

## License

MIT
