# Repository Guidelines

This project is a Spring Boot 4 / Java 21 Maven application for a OneBot QQ bot, using JPA, MySQL, and the Shiro SDK.

## Project Structure & Module Organization

Source code lives under `src/main/java/fun/luqing/dmws`, organized by responsibility:

- `plugin/`: bot commands and plugin entry points, including `tomatoplugin`.
- `plugin/economy/` and `service/dmw/economy/`: current gameplay entry points and business logic; the unused legacy `game/` skeleton has been removed.
- `service/`: business logic, including `dmw/economy` and `dmw/tomato`.
- `entity/` and `repository/`: JPA entities and repositories.
- `config/`, `common/`: framework wiring and shared utilities.
- `resources/`: logging, banner, and `application.example.yaml`.

Tests live under `src/test/java`. Runtime configuration is in `src/main/resources/application.yaml`, which is Git-ignored; use `application.example.yaml` as the template.

## Build, Test, and Development Commands

Run commands from the repository root. Use `mvnw.cmd` instead of `./mvnw` on Windows.

```bash
./mvnw clean verify
./mvnw clean package -DskipTests
./mvnw spring-boot:run
python -m pip install -r scripts/requirements.txt
python -m unittest discover -s scripts -p 'test_*.py' -v
```

- `clean verify` builds the executable jar and runs the complete Java test suite from a clean output directory.
- Python `unittest` runs maintenance-script safety checks without connecting to production services.
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

## Module Dependency Boundaries

- Keep transport entry points in `plugin/` and `mcp/`; business services and clients must not import these packages.
- Keep shared logging context in `common/logging/`, so services and clients can reuse it without depending on plugins.
- Keep tomato and economy services under `service/dmw/tomato/` and `service/dmw/economy/` respectively.
- In MCP, `DmwMcpController` handles HTTP/session protocol, `McpToolCatalog` owns public tool schemas, and `DmwMcpTools` routes calls and wraps results/errors. Feature adapters own business argument handling.
- Changes to tool names, schemas, or return fields must update the catalog, routing tests, and `docs/mcp.md` together.
- `dmw_economy_use_item` defaults to the bot's own account (`dmws.bot-id` or sole connected bot), never the master/local operator. Acting for a requesting group member requires an explicit user_id from trusted message context. Keep item consumption, effects and request deduplication in the same economy transaction; reserve the operation before stock validation so retries after the last item remain recognizable. Balance/backpack `as_bot=true` selects that same bot account and omitted queries select the bot account.
- `service/old/SeTuService` is still used by the image plugin, along with `HttpUtil` and `ImageDownloadUtil`; keep these active features. Unused TTS clients and fixed legacy TTS endpoints have been removed.
- `ConfigManager` is a compatibility facade; `common/server/ImageFileServerLifecycle` owns image-server startup/shutdown. New consumers should inject `DmwConfig` or `BotContainer` directly when those are all they need.
- Ledger storage mutations must join an existing `dmwTransactionManager` transaction; do not split account and ledger-entry writes into independent transactions.
- Only medical settlement may create/increase negative MAIN_BANK balances; ordinary transactions may credit a negative bank balance to repay it, but never create wallet debt or allow shopping/bail/withdrawal overdrafts. Keep hospital/jail deadlines and rate snapshots persistent, cap medical billing at the stored deadline/24 hours, and use per-stay operation IDs. Lock both player profiles in ascending ID order before refreshing their state/account locks. Month cards activate once for 30 local calendar days with an exclusive expiry date; do not consume a card for every daily sign-in.
- All injury grades remain hospitalized until the owner requests discharge or 24 hours from admission elapse; severity changes fees, never a shorter automatic-discharge deadline. Robbery human replies must not expose either balance, injured QQ IDs, or raw ISO/nanosecond timestamps. Reuse the shared robbery narration for QQ and MCP, and derive player game locations from refreshed hospital/jail state.
- Player-facing names use current group cards, then QQ nicknames, then saved profile names; never fall back to a raw player ID. Name resolution must be read-only and must not turn a completed payment into a failure. Keep IDs in structured MCP fields/audit records and retain functional item/bank/red-packet identifiers. Action replies are concise receipts; only explicit queries return full status/balances, and zero medical debt is omitted from human text.
- Private-bank loan operations live in `EconomyPrivateBankLoanService`; keep only one scheduled overdue-marking entry point.
- Six unused legacy game entity mappings have been retired after production empty-table verification. Keep the active `fish` entity/table. Current money/items are owned by the economy module; see `docs/economy.md` before integrating legacy actions or changing table mappings.
- Historical HuYan rows are archived in `dmw.economy_history_*`; `hy_economy` source tables have been retired. Never rerun the initial-import SQL against the live economy. Use the audited reconciliation script and preserve per-stack correction records. Mirai economy-core has also been retired after dependency/restore checks; the `economy` schema is now empty. Preserve the archived history and retirement backups.
- Configure OneBot through `shiro.ws.client`; the unused `dmws.ws-url`, `dmws.http-url`, and `dmws.max-messages` fields have been removed. The config cleanup tool supports a dry run, preserves current MCP settings, and backs up original bytes outside the repository before writing.

- Heatmap level 0 is reserved for dates without chapter updates. Use daily body-character quartiles from complete active dates in the displayed window for levels 1–4; chapter count must not influence intensity, and equal character totals must remain the same color. Active dates with incomplete character counts use level 1 and do not influence quartiles. Use the GitHub dark contribution palette and a compact header/calendar layout. Label counts as body characters; preserve legacy MCP word_count/total_words aliases and identify the metric explicitly.

- Economy MCP writes require `EconomyMcpContext` verification of the HMAC envelope supplied by `scripts/astrbot_dmw_guard`; never trust model-provided player identity or replace this check with group membership. Actor defaults are the bot, except purchases default to the requesting member. Share original-message operation IDs with native commands and reserve them with INSERT, never merge. Keep deterministic success/failure/unknown receipts in the AstrBot adapter, including partial success and timeouts. Preserve the secret outside Git.

- Novel reads use `TomatoReadingService` and downloader v0.3 `tnd_get_chapters`. Interactive reads default to local-only and return pending/partial state immediately; only explicit download intent may enqueue missing IDs. Preserve requested item IDs/order, cached directory mapping, errors and truncation flags; never cache truncated bodies as complete or infer order from downloader slots/ID magnitude. Batch cancellation/retry requires explicit IDs and truthful pending/terminal receipts; refresh content after completion. `scripts/astrbot_tomato_guard` handles novel receipts independently of the economy guard. Keep MCP request IDs correlated and support stateless servers without replaying uncertain writes.

- Fishing preserves stored historical rod levels, ownership and catch records. New rewards use actual size (0.75–1.25; fixed size 1.0) times a linear rod multiplier `1 + .02*(clamp(level,1,100)-1)`; upgrades stop at100, but never truncate stored higher levels. Buying an unowned historical rod retains its level. Show fish descriptions and signed per-cast net income (reward minus fee), omit wallet balances, and disclose that bait acquisition cost is excluded. Keep the existing daily crime decay; community-service/donation reductions remain proposals until requested.

- Electric batons remain owned inventory; each unit has a persistent 100-point battery and valid non-empty-wallet robbery spends20, even on counterattack/arrest. Choose a ready baton before queued legacy boosts and never stack/consume both. The reusable charger costs the same as a baton, charges one lowest-energy unit at1 point per minute (100 minutes empty-to-full), persists its clock across restarts, and allows interruption during robbery: settle accrued energy, stop charging and spend20 when energy>=20; otherwise leave the charging anchor untouched. Read-only views never allocate units or reset charging. Golden basins clear ALL current crime, quantity1 per use, maximum2 per game-local calendar day; they never shorten stays. Reserve operations and hold player profile locks before stock, charge or crime updates, and keep all effects in the same transaction. Preserve inventory stacks and historical queued boosts; no retrospective conversion of spent items.
- Novel human receipts hide internal UUID job/batch IDs but preserve exact IDs in structured results/state. Deduplicate identical progress within one source event; allow a later truthful terminal receipt. Subscription registration and chapter backfill remain separate, and completed receipts require done=true. Restart-expired job IDs do not imply lost subscriptions; query persisted subscriptions before retrying writes.
