# Repository Guidelines

This project is a Spring Boot 4 / Java 21 Maven application for a OneBot QQ bot, using JPA, MySQL, and the Shiro SDK.

## Project Structure & Module Organization

Source code lives under `src/main/java/fun/luqing/dmws`, organized by responsibility:

- `plugin/`: bot commands and plugin entry points, including `tomatoplugin`.
- `game/`: gameplay actions and their interfaces/implementations.
- `service/`: business logic, including `dmw/economy` and `tomatoservice`.
- `entity/` and `repository/`: JPA entities and repositories.
- `config/`, `common/`: framework wiring and shared utilities.
- `resources/`: logging, banner, and `application.example.yaml`.

Tests live under `src/test/java`. Runtime configuration is in `src/main/resources/application.yaml`, which is Git-ignored; use `application.example.yaml` as the template.

## Build, Test, and Development Commands

Run commands from the repository root. Use `mvnw.cmd` instead of `./mvnw` on Windows.

```bash
./mvnw test
./mvnw clean package -DskipTests
./mvnw spring-boot:run
```

- `test` compiles and runs JUnit tests.
- `clean package` produces the executable Spring Boot jar.
- `spring-boot:run` starts the bot locally. It needs a valid `application.yaml` and reachable MySQL databases.

## Coding Style & Naming Conventions

- Indent with four spaces and keep the UTF-8 source encoding configured in `pom.xml`.
- Use PascalCase for classes, camelCase for methods and variables, and `UPPER_SNAKE_CASE` for constants.
- Follow package-by-feature naming: `plugin`, `service`, `entity`, `repository`, and `config`.
- No formatter or linter is enforced; match the style of nearby files and prefer Lombok annotations where they reduce boilerplate.

## Testing Guidelines

Tests use JUnit 5 and `@SpringBootTest`. Name test classes `*Tests`; the existing `DmwsApplicationTests` is a context-load smoke test. Add focused tests for new services and repositories.

## Commit & Pull Request Guidelines

Follow Conventional Commits with a Simplified Chinese subject:

```text
<type>(<scope>): <subject>
```

Use types such as `feat`, `fix`, `docs`, `refactor`, `perf`, `test`, `build`, `ci`, and `chore`. Choose a concrete scope such as `plugin`, `tomato`, `economy`, or `config`. Keep the subject under 50 characters and add a detailed body listing the affected module, file, class, change, and reason when meaningful. Reference issues with `Closes #123` or `Refs #123`.

For pull requests, summarize intent and behavior changes, state the commands run, include screenshots when a command response or generated image is affected, and never commit real API keys or credentials.

## Security & Configuration Tips

Do not commit `application.yaml`, API keys, bot tokens, database passwords, or other secrets. Update `application.example.yaml` when configuration surface changes, and keep real credentials in the ignored local file.
