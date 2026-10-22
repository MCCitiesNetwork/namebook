# Namebook — Design

Date: 2026-10-22
Status: Draft for review

## Purpose

Namebook is the one place plugins ask "what is this player called?" and
"which player has this name?". Today several plugins answer those questions
in their own way, each with its own gaps. Namebook gives them one answer that
is always current, works for Bedrock players, and works for players the
server's own cache has forgotten.

Success looks like this:

- A plugin can turn a UUID into the player's current name, and a name into a
  UUID, with one call, from any thread, without it ever throwing.
- A player who renames shows their new name everywhere as soon as they join.
- Bedrock (Floodgate) names, which start with `.`, resolve exactly.
- It runs on one server today, and on several servers sharing one database
  later, with no code change.

## Scope

In scope:

- Recording every player's UUID and current name on join.
- Lookups by UUID and by name, single and bulk, case-insensitive for names.
- A published API module other plugins compile against.
- An in-memory cache per server.
- Fallbacks for players Namebook has not seen: the server's own user cache,
  then playerdb.co, then Mojang (each configurable).
- Seeding the table from the server's user cache with an admin command.
- An admin lookup command.

Out of scope:

- Name history. Only the current name is kept.
- Anything else about players (last seen, first join, playtime, IPs, alts).
- Cross-server cache invalidation. Each server's cache simply expires; a few
  minutes of staleness after a rename on another server is acceptable.
- Changing Realty or Treasury. They can adopt Namebook later; Pollster is the
  first user.

## Platform

- Paper 26.3 and later only. `api-version: "26.3"`.
- Java 25. Gradle Kotlin DSL with convention plugins in `buildSrc`,
  configuration-cache clean, version from `-PreleaseVersion`
  (default `1.0.0-SNAPSHOT`).
- MariaDB (or MySQL 8+) only.
- Group `com.minecraftcitiesnetwork`, base package
  `com.minecraftcitiesnetwork.namebook`.
- The same stack and conventions as Pollster and Realty: Configurate YAML
  records, MyBatis annotation mappers, plugin-infrastructure's
  `SchemaMigrator`, Incendo Cloud commands, JUnit 5, Mockito, Testcontainers.

## Modules

| Module | Role | Bukkit? |
|---|---|---|
| `namebook-api` | The `Namebook` interface and its finder. Published to Maven. | Interface: no. Finder: compile-only Paper. |
| `namebook-core` | Name rules, cache, lookup chain, MyBatis storage, migrations. | No |
| `namebook-paper` | The plugin: configs, join listener, user-cache source, Mojang source, commands. | Yes |

## API

```java
package com.minecraftcitiesnetwork.namebook.api;

public interface Namebook {

    /** The player's current name, or empty when no source knows the UUID. */
    CompletableFuture<Optional<String>> nameOf(UUID player);

    /** The UUID of the player who currently has this name (case-insensitive), or empty. */
    CompletableFuture<Optional<UUID>> uuidOf(String name);

    /** Every requested UUID is a key, in request order. */
    CompletableFuture<Map<UUID, Optional<String>>> namesOf(Collection<UUID> players);

    /** Every requested name is a key, in request order, with the caller's spelling. */
    CompletableFuture<Map<String, Optional<UUID>>> uuidsOf(Collection<String> names);
}
```

Guarantees, documented on the interface:

- Every method is safe from any thread and never completes exceptionally.
  A failure in any source counts as "unknown" for that source.
- Futures complete on Namebook's own executor, never the main thread.
  Callers that touch Bukkit hop back themselves.
- Bulk calls deduplicate and make at most one database round trip for the
  identifiers the cache does not hold.

`NamebookFinder.find(ServicesManager)` returns `Optional<Namebook>`, so a
plugin can depend on Namebook softly and fall back when it is not installed.
The plugin registers its implementation with the services manager at
`ServicePriority.Normal`.

The API module has no runtime dependencies and is published as
`com.minecraftcitiesnetwork:namebook-api` to
`https://maven.minecraftcitiesnetwork.com/{releases|snapshots}`.

## Names

- A Java name matches `^[A-Za-z0-9_]{1,16}$`. A Bedrock (Geyser/Floodgate)
  name is built exactly as Floodgate builds it: the prefix, then the gamertag
  cut so the whole name is at most 16 characters, with spaces replaced by `_`
  when Floodgate's `replace-spaces` is on (the default). Gamertags are ASCII
  letters, digits and spaces, so every valid name is ASCII. Anything else is
  rejected by `uuidOf` (empty result) and never stored.
- A Bedrock name of exactly 16 characters may have been cut short, so it is
  never looked up by gamertag remotely: a partial gamertag could match a
  different Xbox account. Such players are still found through the database
  and the server cache once they have joined.
- The Bedrock prefix is `bedrock.prefix` (default `.`) and space handling is
  `bedrock.replace-spaces` (default true). When Floodgate is installed,
  Namebook reads Floodgate's `username-prefix` and `replace-spaces` instead,
  so the two always agree. A prefix made of name characters (letters, digits,
  `_`) makes Bedrock and Java names ambiguous; Namebook warns about it at
  startup, as Floodgate itself advises against it. An empty prefix is allowed; Bedrock names then look like Java
  names and are resolved by name the Java way.
- A Floodgate UUID has its high 64 bits all zero; its low 64 bits are the
  player's Xbox user ID (XUID). Players who linked a Java account join with
  their Java UUID and name and are ordinary Java players to Namebook.
- Names are compared case-insensitively. They are stored and returned with
  the player's own capitalisation.
- A name belongs to at most one player at a time, as in Minecraft itself.
  When a player joins with a name another row holds, that other row is
  deleted: its player has since renamed, and their next lookup falls through
  to the remote sources, which know the new name.

## Storage

One table, prefixed so the database can be shared:

```sql
CREATE TABLE IF NOT EXISTS namebook_players (
  player_uuid  BINARY(16)   NOT NULL PRIMARY KEY,
  name         VARCHAR(32)  NOT NULL,
  UNIQUE KEY uq_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
```

- The case-insensitive collation makes `WHERE name = ?` and the unique key
  case-insensitive, so `Steve` and `steve` are the same name.
- No timestamps are stored.
- Migrations live in `namebook-core` as `V{n}__name.sql`, applied by
  plugin-infrastructure's `SchemaMigrator` with the version table
  `namebook_schema_version`.

## Lookup chain

Each lookup tries sources in order and stops at the first answer:

1. **Cache.** In memory, per server. Entries expire after `cache.ttl`
   (default 10 minutes); at most `cache.max-entries` (default 10 000), oldest
   evicted first. Misses are cached too, for `cache.miss-ttl` (default
   1 minute), so repeated lookups of an unknown player do not hit the
   database each time.
2. **Database.**
3. **Server user cache.** `Server#getOfflinePlayer(UUID)#getName()` for names,
   `Server#getOfflinePlayerIfCached(String)` for UUIDs. Read only; never
   triggers a web lookup.
4. **playerdb.co.** Requests send a `User-Agent` naming Namebook and its
   version, as playerdb.co asks; `success: false` means unknown.
   - Java: `GET https://playerdb.co/api/player/minecraft/{name or uuid}`
     (`data.player.username`, `data.player.id`).
   - Bedrock by UUID: the XUID (the UUID's low 64 bits) is looked up with
     `GET https://playerdb.co/api/player/xbox/{xuid}`; the gamertag in
     `data.player.username` becomes prefix + gamertag with spaces as `_`.
   - Bedrock by name (names shorter than 16 characters only): the prefix is
     removed and every `_` and space deleted (gamertags cannot contain `_`, so
     each one was a space, and the endpoint does not accept spaces), then `GET https://playerdb.co/api/player/xbox/{gamertag}`;
     the XUID in `data.player.id` becomes the Floodgate UUID.
   - An answer that does not match the request (a different UUID, or a name
     that differs other than in case) counts as unknown.
5. **Mojang.** The official profile endpoints, used for Java players when
   playerdb.co is disabled, unknown, slow or failing. Never used for Bedrock
   names or Floodgate UUIDs, which Mojang does not know.
Each has its own `enabled` (default true), `timeout` (default 3 seconds) and
`max-per-minute` (default 30); a request over the limit counts as unknown for
that source and the chain moves on.

An answer found in steps 3–5 is cached but not written to the database.
The database records only players this network has actually seen join.

## Recording joins

- On `PlayerJoinEvent`, Namebook, on its database executor and in one
  transaction, deletes any other row holding the player's name and upserts
  the player's UUID and current name. It puts the name in the cache straight
  away so lookups on this server are correct at once.
- On enable, every player already online is recorded the same way (for
  `/reload` and late loading).
- A failed write is logged once per player per minute and does not affect
  the join.

## Commands

| Command | Permission | Default | Effect |
|---|---|---|---|
| `/namebook lookup <name or uuid>` | `namebook.lookup` | op | Shows the other half and which source answered (cache, database, server, playerdb.co or Mojang). |
| `/namebook import usercache` | `namebook.admin` | op | Reads the server's `usercache.json` and inserts every entry whose UUID and name are both absent from the table. Reports how many rows were added and how many were skipped. Never changes an existing row. |
| `/namebook reload` | `namebook.admin` | op | Reloads `settings.yml` and clears the cache. |

## Configuration

Configurate YAML records with defaults and validation in compact
constructors; bundled defaults are merged into the live file and a reference
copy is written to `defaults/`, as Pollster does.

| File | Contents |
|---|---|
| `database.yml` | `url` (without `jdbc:`), `username`, `password`. Ships blank; the plugin disables itself with an explanation when `url` is empty. |
| `settings.yml` | `bedrock.prefix`; `cache.ttl`, `cache.miss-ttl`, `cache.max-entries`; `playerdb.enabled`, `playerdb.timeout`, `playerdb.max-per-minute`; `mojang.enabled`, `mojang.timeout`, `mojang.max-per-minute`. |
| `messages.yml` | Command output, MiniMessage. Player-supplied text inserted unparsed. |

## Several servers

Point every server's `database.yml` at the same database. Every server
records its own joins, so the table covers the whole network. Each server's
cache is independent and expires on its own; no messaging between servers
is needed.

## Pollster integration

Pollster already resolves names through its `PlayerNames` interface. It gains
a `NamebookPlayerNames` implementation, used when
`NamebookFinder.find(...)` returns a service at enable time, and otherwise
keeps its server-cache fallback. `paper-plugin.yml` declares Namebook as an
optional dependency that loads first. This is the last task, in the
Pollster repository.

## Testing

- `namebook-core`: name rules; cache expiry, miss caching and eviction; the
  lookup chain order and short-circuiting with fake sources; bulk
  deduplication and single round trip; a failing source treated as unknown;
  never-throwing guarantees. Storage with Testcontainers MariaDB: upsert,
  case-insensitive lookup, a join taking a name from another row, the unique
  key rejecting duplicates that differ only in case, migrations.
- `namebook-paper`: config guard tests; the user-cache importer against a
  sample `usercache.json` (adds missing, never overwrites joins); Mojang
  and playerdb.co sources against a local HTTP server (found, unknown,
  malformed body, timeout, rate limit, Bedrock by XUID and by gamertag,
  mismatched answers); playerdb.co is asked before Mojang, Mojang only when
  playerdb.co gives no answer, and never for Bedrock. Bedrock name rules with
  `.`, a custom prefix and an empty prefix; Floodgate UUID ↔ XUID conversion
  (XUID 2584878536129841 ↔ `00000000-0000-0000-0009-2eeeeeeefd31`).
- Test names follow `subject_condition`.

## Repository

- Public repository `MCCitiesNetwork/namebook`.
- CI builds and tests every push. Pushes to `main` publish `namebook-api`
  snapshots to maven.minecraftcitiesnetwork.com using the organization secrets
  `MAVEN_USERNAME` and `MAVEN_PASSWORD`. Tags `v*` publish releases.
- `.gitignore` covers build output, IDE files, `run/`, `.env` and `local/`.
- Committed files never contain machine-specific paths or personal
  identifiers. A local hook outside the repository enforces this on every
  commit and push.
