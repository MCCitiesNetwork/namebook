package com.minecraftcitiesnetwork.namebook.paper;

import com.minecraftcitiesnetwork.namebook.api.Namebook;
import com.minecraftcitiesnetwork.namebook.core.cache.NameCache;
import com.minecraftcitiesnetwork.namebook.core.chain.LookupChain;
import com.minecraftcitiesnetwork.namebook.core.importer.UserCacheImporter;
import com.minecraftcitiesnetwork.namebook.core.limit.RateLimiter;
import com.minecraftcitiesnetwork.namebook.core.name.NameRules;
import com.minecraftcitiesnetwork.namebook.core.remote.MojangSource;
import com.minecraftcitiesnetwork.namebook.core.remote.PlayerDbSource;
import com.minecraftcitiesnetwork.namebook.core.source.NameSource;
import com.minecraftcitiesnetwork.namebook.core.store.DatabaseSource;
import com.minecraftcitiesnetwork.namebook.core.store.NamebookDatabase;
import com.minecraftcitiesnetwork.namebook.core.store.PlayerStore;
import com.minecraftcitiesnetwork.namebook.paper.command.NamebookCommand;
import com.minecraftcitiesnetwork.namebook.paper.config.ConfigLoader;
import com.minecraftcitiesnetwork.namebook.paper.config.DatabaseConfig;
import com.minecraftcitiesnetwork.namebook.paper.config.Messages;
import com.minecraftcitiesnetwork.namebook.paper.config.Settings;
import com.minecraftcitiesnetwork.namebook.paper.listener.JoinRecorder;
import com.minecraftcitiesnetwork.namebook.paper.source.ServerCacheSource;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.PaperCommandManager;
import org.incendo.cloud.paper.util.sender.PaperSimpleSenderMapper;
import org.incendo.cloud.paper.util.sender.Source;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plugin entry point and composition root. Every collaborator is constructed here and receives
 * its dependencies through its constructor.
 */
public final class NamebookPlugin extends JavaPlugin {

    private static final URI PLAYERDB = URI.create("https://playerdb.co");
    private static final URI MOJANG_SESSION = URI.create("https://sessionserver.mojang.com");
    private static final URI MOJANG_API = URI.create("https://api.mojang.com");
    private static final String PROJECT_URL = "https://github.com/MCCitiesNetwork/namebook";
    /** Database calls block, so a small fixed pool keeps them from exhausting the connection pool. */
    private static final int DATABASE_THREADS = 4;

    private final AtomicReference<Settings> settings = new AtomicReference<>();
    private final AtomicReference<Messages> messages = new AtomicReference<>();
    private @Nullable ExecutorService database;
    private @Nullable ExecutorService network;
    private @Nullable HttpClient http;
    private @Nullable NamebookDatabase storage;
    private @Nullable NameCache cache;

    /** How Bedrock names are built and whether that came from Floodgate's own configuration. */
    public record BedrockNames(@NotNull String prefix, boolean replaceSpaces, boolean fromFloodgate) {
    }

    @Override
    public void onEnable() {
        ConfigLoader loader = new ConfigLoader(getDataFolder().toPath(), getClassLoader(), getLogger());
        DatabaseConfig databaseConfig;
        try {
            databaseConfig = loader.load("database.yml", DatabaseConfig.class);
            this.settings.set(loader.load("settings.yml", Settings.class));
            this.messages.set(loader.loadMessages());
        } catch (Exception ex) {
            getLogger().severe("Could not read Namebook's configuration, disabling: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (databaseConfig.isBlank()) {
            getLogger().severe("database.yml has no url. Namebook needs a MariaDB database: fill in "
                    + "plugins/Namebook/database.yml, then restart. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Settings current = this.settings.get();

        BedrockNames bedrock = resolveBedrockNames(getDataFolder().toPath().getParent(),
                current.bedrock(), getLogger());
        getLogger().info("Bedrock name prefix is '" + bedrock.prefix() + "', spaces "
                + (bedrock.replaceSpaces() ? "replaced by _" : "kept") + ", "
                + (bedrock.fromFloodgate() ? "read from Floodgate's config.yml" : "from settings.yml"));
        NameRules rules = new NameRules(bedrock.prefix(), bedrock.replaceSpaces());
        warnIfAmbiguous(rules, getLogger());

        // Threads need the plugin class loader so the JDBC driver and MyBatis resources resolve.
        ClassLoader pluginClassLoader = getClassLoader();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "Namebook database");
            thread.setContextClassLoader(pluginClassLoader);
            return thread;
        };
        ExecutorService databaseExecutor = Executors.newFixedThreadPool(DATABASE_THREADS, threadFactory);
        ExecutorService networkExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.database = databaseExecutor;
        this.network = networkExecutor;

        // Opening runs the migrations; blocking here keeps the plugin from enabling without a schema.
        NamebookDatabase opened;
        try {
            opened = databaseExecutor.submit(() -> NamebookDatabase.open(databaseConfig.toSettings(), getLogger()))
                    .get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            failEnable("Interrupted while opening the database.", ex);
            return;
        } catch (ExecutionException ex) {
            failEnable("Could not open the database: " + ex.getCause().getMessage(), ex.getCause());
            return;
        }
        this.storage = opened;
        PlayerStore store = new PlayerStore(opened);

        Clock clock = Clock.systemUTC();
        Settings.Cache cacheSettings = current.cache();
        NameCache nameCache = new NameCache(clock, cacheSettings.ttl(), cacheSettings.missTtl(),
                cacheSettings.maxEntries());
        this.cache = nameCache;

        HttpClient client = HttpClient.newBuilder()
                .executor(networkExecutor)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.http = client;
        String userAgent = "Namebook/" + getPluginMeta().getVersion() + " (+" + PROJECT_URL + ")";
        List<NameSource> fallbacks = new ArrayList<>();
        fallbacks.add(new ServerCacheSource(getServer(), networkExecutor));
        if (current.playerdb().enabled()) {
            fallbacks.add(new PlayerDbSource(client, PLAYERDB, current.playerdb().timeout(),
                    new RateLimiter(clock, current.playerdb().maxPerMinute()), userAgent, rules));
        }
        if (current.mojang().enabled()) {
            fallbacks.add(new MojangSource(client, MOJANG_SESSION, MOJANG_API, current.mojang().timeout(),
                    new RateLimiter(clock, current.mojang().maxPerMinute()), userAgent, rules));
        }
        // Answers complete on virtual threads so a slow caller never holds up a database thread.
        LookupChain chain = new LookupChain(rules, nameCache, new DatabaseSource(store, databaseExecutor, getLogger()),
                fallbacks, networkExecutor, getLogger());
        getServer().getServicesManager().register(Namebook.class, chain, this, ServicePriority.Normal);

        JoinRecorder recorder = new JoinRecorder(store, chain, rules, databaseExecutor, clock, getLogger());
        getServer().getPluginManager().registerEvents(recorder, this);

        PaperCommandManager<Source> commands = PaperCommandManager
                .builder(PaperSimpleSenderMapper.simpleSenderMapper())
                .executionCoordinator(ExecutionCoordinator.<Source>simpleCoordinator())
                .buildOnEnable(this);
        // usercache.json lives in the world container, which is the server folder unless --universe moves it.
        Path userCache = getServer().getWorldContainer().toPath().resolve("usercache.json");
        new NamebookCommand(chain, rules, new UserCacheImporter(store, rules), userCache, databaseExecutor,
                this::reload, this.messages::get, getLogger()).register(commands);

        // After a /reload or a late enable, players already online have joined without being recorded.
        recorder.recordOnline(getServer().getOnlinePlayers());
        getLogger().info("Namebook enabled");
    }

    private void failEnable(String message, Throwable cause) {
        getLogger().severe(message);
        getLogger().log(Level.FINE, "Enable failure detail", cause);
        getServer().getPluginManager().disablePlugin(this);
    }

    @Override
    public void onDisable() {
        // Reverse of enable. Cloud's Paper manager registers through the lifecycle API, which has
        // no unregister; Paper drops a disabled plugin's commands itself.
        HandlerList.unregisterAll(this);
        getServer().getServicesManager().unregisterAll(this);
        if (this.http != null) {
            this.http.shutdownNow();
            this.http = null;
        }
        // Drain queued join writes before closing the pool they use.
        shutdown(this.network, getLogger());
        shutdown(this.database, getLogger());
        this.network = null;
        this.database = null;
        if (this.storage != null) {
            this.storage.close();
            this.storage = null;
        }
        this.cache = null;
    }

    /**
     * Re-reads {@code settings.yml} and {@code messages.yml} off the main thread and clears the
     * cache. The prefix, cache sizes and remote sources are built once at enable, so a change to
     * them is reported as needing a restart.
     */
    public @NotNull CompletableFuture<Void> reload() {
        ExecutorService executor = this.database;
        NameCache nameCache = this.cache;
        if (executor == null || nameCache == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Namebook is not enabled."));
        }
        ConfigLoader loader = new ConfigLoader(getDataFolder().toPath(), getClassLoader(), getLogger());
        return CompletableFuture.runAsync(() -> {
            Settings newSettings;
            Messages newMessages;
            try {
                newSettings = loader.load("settings.yml", Settings.class);
                newMessages = loader.loadMessages();
            } catch (ConfigurateException ex) {
                throw new IllegalStateException(ex.getMessage(), ex);
            }
            Settings previous = this.settings.getAndSet(newSettings);
            this.messages.set(newMessages);
            nameCache.clear();
            if (previous != null && !previous.equals(newSettings)) {
                getLogger().warning("Changes to settings.yml take effect after a server restart; "
                        + "the reload applied messages.yml and cleared the cache.");
            }
        }, executor);
    }

    /**
     * Floodgate's own {@code username-prefix} and {@code replace-spaces} win when Floodgate is
     * installed, so Namebook and Floodgate can never disagree about a Bedrock player's name.
     *
     * @param pluginsFolder the server's plugins folder
     * @param configured    {@code bedrock} from settings.yml
     */
    static @NotNull BedrockNames resolveBedrockNames(@NotNull Path pluginsFolder,
                                                     @NotNull Settings.Bedrock configured, @NotNull Logger log) {
        BedrockNames fallback = new BedrockNames(configured.prefix(), configured.replaceSpaces(), false);
        Path floodgateConfig = pluginsFolder.resolve("floodgate").resolve("config.yml");
        if (!Files.isRegularFile(floodgateConfig)) {
            return fallback;
        }
        try {
            ConfigurationNode root = YamlConfigurationLoader.builder().path(floodgateConfig).build().load();
            String prefix = root.node("username-prefix").getString();
            if (prefix == null) {
                log.warning("Floodgate's config.yml has no username-prefix; using bedrock from settings.yml.");
                return fallback;
            }
            ConfigurationNode replaceSpaces = root.node("replace-spaces");
            boolean replace = replaceSpaces.virtual()
                    ? configured.replaceSpaces()
                    : replaceSpaces.getBoolean(configured.replaceSpaces());
            return new BedrockNames(prefix, replace, true);
        } catch (ConfigurateException ex) {
            log.warning("Could not read Floodgate's config.yml (" + ex.getMessage()
                    + "); using bedrock from settings.yml.");
        }
        return fallback;
    }

    /** Floodgate itself advises against such a prefix, because Java names can then look like Bedrock names. */
    static void warnIfAmbiguous(@NotNull NameRules rules, @NotNull Logger log) {
        if (rules.prefixIsAmbiguous()) {
            log.warning("The Bedrock prefix '" + rules.bedrockPrefix() + "' contains letters, digits or _, so "
                    + "some Java names look like Bedrock names and are looked up as Bedrock players. "
                    + "Use a prefix such as '.' in Floodgate's config.yml.");
        }
    }

    private static void shutdown(@Nullable ExecutorService service, Logger log) {
        if (service == null) {
            return;
        }
        service.shutdown();
        try {
            if (!service.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warning("Namebook executor did not stop within 30 seconds; forcing shutdown.");
                service.shutdownNow();
            }
        } catch (InterruptedException ex) {
            service.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
