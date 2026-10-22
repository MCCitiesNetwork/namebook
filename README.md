# Namebook

Namebook is a Paper plugin that answers two questions for other plugins: "what is this player called?"
and "which player has this name?". It records every player's UUID and current name when they join,
keeps the answers in a MariaDB database, and falls back to other sources for players it has not seen.
Java and Bedrock (Geyser/Floodgate) players are both supported.

## Requirements

- Paper 26.3 or later
- Java 25
- MariaDB (or MySQL 8+)

## Install

1. Put the Namebook jar in your server's `plugins` folder.
2. Start the server once. Namebook creates its config files and then disables itself, because the
   database is not set up yet.
3. Fill in `plugins/Namebook/database.yml` (see below) and restart.

## Java and Bedrock players

- A Java name is 1 to 16 letters, digits or underscores. Names are compared without regard to case,
  and are stored and shown with the player's own capitalisation.
- A Bedrock name is the Bedrock prefix followed by the player's gamertag, with spaces written as `_`.
  The prefix is `bedrock.prefix` in `settings.yml` (default `.`). When Floodgate is installed,
  Namebook uses Floodgate's `username-prefix` instead, so the two always agree.
- Players who linked a Java account to Floodgate join as ordinary Java players.
- Names that fit neither form are never looked up and never stored.

## How a lookup works

Namebook tries these sources in order and stops at the first answer:

1. **Cache.** In memory on this server. Unknown players are cached briefly too.
2. **Database.** Every player who has joined a server using this database.
3. **Server user cache.** Paper's own list of recent players. It is only read, never refreshed.
4. **playerdb.co.** Java and Bedrock.
5. **Mojang.** Java only. Used when playerdb.co is off or has no answer.

Answers from steps 3 to 5 are cached but not written to the database; the database holds only players
Namebook has seen join. If a source fails or is over its rate limit, Namebook treats it as "unknown" and
moves on to the next one.

A name belongs to one player at a time. When a player joins with a name that another row holds, the other
row is removed, because that player has since renamed.

## Config files

All files are in `plugins/Namebook/`. A reference copy of each default is written to `defaults/`.

- `database.yml`: `url` (without the `jdbc:` prefix, for example `mariadb://localhost:3306/namebook`),
  `username` and `password`. It ships blank.
- `settings.yml`:
  - `bedrock.prefix`: the Bedrock name prefix (default `.`; may be empty).
  - `cache.ttl` (10 minutes), `cache.miss-ttl` (1 minute) and `cache.max-entries` (10 000).
  - `playerdb.enabled`, `playerdb.timeout` (3 seconds) and `playerdb.max-per-minute` (30).
  - `mojang.enabled`, `mojang.timeout` (3 seconds) and `mojang.max-per-minute` (30).
- `messages.yml`: command output, written in MiniMessage.

Namebook uses one table, `namebook_players`, plus `namebook_schema_version` for upgrades. Both are created
and updated automatically.

## Commands and permissions

| Command | Permission | Default | What it does |
|---|---|---|---|
| `/namebook lookup <name or uuid>` | `namebook.lookup` | op | Shows the other half and which source answered. |
| `/namebook import usercache` | `namebook.admin` | op | Adds players from the server's `usercache.json` that are not in the database yet. Never changes an existing row. |
| `/namebook reload` | `namebook.admin` | op | Reloads `settings.yml` and clears the cache. |

Run `/namebook import usercache` once after installing, so players who joined before Namebook are known.

## Running on several servers

Point every server's `database.yml` at the same database. Each server records its own joins, so the table
covers your whole network. Each server keeps its own cache, which expires by itself, so after a rename on
one server another may show the old name for a few minutes. No messaging between servers is needed.

## Using the API

Namebook's API is published to the Minecraft Cities Network Maven repository.

```kotlin
repositories {
    maven {
        name = "minecraftcitiesnetwork-snapshots"
        url = uri("https://maven.minecraftcitiesnetwork.com/snapshots")
    }
}

dependencies {
    compileOnly("com.minecraftcitiesnetwork:namebook-api:1.0.0-SNAPSHOT")
}
```

For a release version, use the `releases` repository at
`https://maven.minecraftcitiesnetwork.com/releases` and the release number.

`Namebook` has four methods: `nameOf(UUID)`, `uuidOf(String)`, `namesOf(Collection<UUID>)` and
`uuidsOf(Collection<String>)`. Each returns a `CompletableFuture`, can be called from any thread, and never
completes with an exception: a failure means "unknown". Futures complete on Namebook's own thread, not the
main thread, so hop back to the main thread before touching Bukkit. Bulk calls return every requested key
and make at most one database query.

To depend on Namebook softly, find it through the services manager and fall back when it is missing:

```java
import com.minecraftcitiesnetwork.namebook.api.Namebook;
import com.minecraftcitiesnetwork.namebook.api.NamebookFinder;

Optional<Namebook> namebook = NamebookFinder.find(getServer().getServicesManager());

CompletableFuture<Optional<String>> name = namebook
        .map(book -> book.nameOf(uuid))
        .orElseGet(() -> CompletableFuture.completedFuture(
                Optional.ofNullable(getServer().getOfflinePlayer(uuid).getName())));
```

In `paper-plugin.yml`, declare Namebook as an optional dependency that loads first.

## Build

```sh
./gradlew build
```
