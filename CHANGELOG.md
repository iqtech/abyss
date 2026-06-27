## [0.6.0] - 2026-06-27

- Add `modifyEdge(old, new)` for atomic edge retargeting with integrity check support
- Tune YSQL HikariCP pool: configurable `ysqlMaxPoolSize` (default 20), warm idle, server-side prepared statement caching

## [0.5.0] - 2026-06-27

- Add edge integrity check on creation (`AbyssError.IntegrityError`); disable via `checkIntegrity = false` for bulk loads
- Make YSQL schema and YCQL keyspace configurable to support multiple graphs in one application
- Switch `java.util.UUID` / `java.time.Instant` to `kotlin.uuid.Uuid` / `kotlinx.datetime.Instant` throughout public API
