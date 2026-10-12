# Mimo Backend

Backend base for the Luma API, using Java 21, Spring Boot 3, Gradle and PostgreSQL.

## Local development

1. Copy `.env.example` to `.env` and set a local database password.
2. Start PostgreSQL with `docker compose up -d`.
3. Run the application with `./gradlew bootRun` (Windows: `gradlew.bat bootRun`).
4. Run tests with `./gradlew clean test` (Windows: `gradlew.bat clean test`).

The default `local` profile reads database and CORS settings from the environment. Context tests use H2; PostgreSQL integration tests start PostgreSQL 16 with Testcontainers when Docker is available. To use an existing disposable test database instead, set `POSTGRES_TEST_URL`, `POSTGRES_TEST_USERNAME` and `POSTGRES_TEST_PASSWORD` before running `./gradlew test`. Flyway migrations run against that database, so do not point this setting at data you need to keep. `DB_PORT` changes the host port published by Compose.

Production database and CORS settings have no committed credentials or defaults.

Health is available at `/actuator/health`; OpenAPI is at `/v3/api-docs` and Swagger UI at `/swagger-ui/index.html`. Only the health endpoint and API documentation are public; other routes require a valid JWT.