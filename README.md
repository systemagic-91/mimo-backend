# Mimo Backend

Backend base for the Luma API, using Java 21, Spring Boot 3, Gradle and PostgreSQL.

## Local development

1. Copy `.env.example` to `.env` and set a local database password.
2. Start PostgreSQL with `docker compose up -d`.
3. Run the application with `./gradlew bootRun` (Windows: `gradlew.bat bootRun`).
4. Run tests with `./gradlew clean test` (Windows: `gradlew.bat clean test`).

The default `local` profile reads database and CORS settings from the environment. Context tests use H2; PostgreSQL integration tests start PostgreSQL 16 with Testcontainers when Docker is available. To use an existing disposable test database instead, set `POSTGRES_TEST_URL`, `POSTGRES_TEST_USERNAME` and `POSTGRES_TEST_PASSWORD` before running `./gradlew test`. Flyway migrations run against that database, so do not point this setting at data you need to keep. `DB_PORT` changes the host port published by Compose.

Production database and CORS settings have no committed credentials or defaults.

## Authentication

Access JWTs use HS256 with a 15-minute default lifetime. Set `JWT_SECRET` to a Base64-encoded random key of at least 32 bytes in production; local/test profiles generate an ephemeral key when it is unset, so tokens stop working after a restart. Refresh tokens are opaque, stored only as SHA-256 hashes, rotated on use, and revoked on logout, password changes, or reuse detection. A reused token revokes all active refresh sessions for that user.

The API returns the refresh token in the response body to match the current contract. Browser clients must keep it in memory only, never in local/session storage or logs; it is lost on a full reload and the user must sign in again. An HttpOnly-cookie flow requires a separate CSRF/CORS contract before replacing this choice.

To provision the first owner, set `BOOTSTRAP_OWNER_ENABLED=true`, `BOOTSTRAP_OWNER_NAME`, `BOOTSTRAP_OWNER_EMAIL`, and a one-time `BOOTSTRAP_OWNER_PASSWORD` of at least 12 characters, then start the application once. Bootstrap refuses to overwrite an existing account; unset the credentials and disable the flag immediately afterward.

Health is available at `/actuator/health`; OpenAPI is at `/v3/api-docs` and Swagger UI at `/swagger-ui/index.html`. Only the health endpoint and API documentation are public; other routes require a valid JWT.