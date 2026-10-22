package com.minecraftcitiesnetwork.namebook.paper.command;

import com.minecraftcitiesnetwork.namebook.core.chain.LookupChain;
import com.minecraftcitiesnetwork.namebook.core.importer.ImportResult;
import com.minecraftcitiesnetwork.namebook.core.importer.UserCacheImporter;
import com.minecraftcitiesnetwork.namebook.core.name.NameRules;
import com.minecraftcitiesnetwork.namebook.core.source.Resolution;
import com.minecraftcitiesnetwork.namebook.core.source.SourceId;
import com.minecraftcitiesnetwork.namebook.paper.config.MessageKeys;
import com.minecraftcitiesnetwork.namebook.paper.config.Messages;
import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import org.apache.ibatis.exceptions.PersistenceException;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.paper.util.sender.Source;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static org.incendo.cloud.parser.standard.StringParser.greedyStringParser;

/**
 * {@code /namebook lookup <name or uuid>} ({@code namebook.lookup}), and
 * {@code /namebook import usercache} and {@code /namebook reload} ({@code namebook.admin}).
 */
public final class NamebookCommand {

    private static final String ROOT = "namebook";
    private static final Pattern DASHED_UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern PLAIN_UUID = Pattern.compile("^[0-9a-fA-F]{32}$");

    private final LookupChain chain;
    private final NameRules rules;
    private final UserCacheImporter importer;
    private final Path userCache;
    private final Executor database;
    private final Supplier<CompletableFuture<Void>> reloader;
    private final Supplier<Messages> messages;
    private final Logger log;

    /**
     * @param userCache the server's {@code usercache.json}
     * @param database  where the blocking import runs
     * @param reloader  re-reads the configuration; completes exceptionally when it could not
     */
    public NamebookCommand(@NotNull LookupChain chain, @NotNull NameRules rules, @NotNull UserCacheImporter importer,
                           @NotNull Path userCache, @NotNull Executor database,
                           @NotNull Supplier<CompletableFuture<Void>> reloader, @NotNull Supplier<Messages> messages,
                           @NotNull Logger log) {
        this.chain = chain;
        this.rules = rules;
        this.importer = importer;
        this.userCache = userCache;
        this.database = database;
        this.reloader = reloader;
        this.messages = messages;
        this.log = log;
    }

    public void register(@NotNull CommandManager<Source> manager) {
        this.commands(manager.commandBuilder(ROOT)).forEach(manager::command);
    }

    /** @param root a builder already at the {@code namebook} literal */
    public @NotNull List<Command<? extends Source>> commands(@NotNull Command.Builder<Source> root) {
        return List.of(
                root.literal("lookup")
                        // Greedy: Brigadier's single word refuses characters such as '*' or '%', so a
                        // custom Bedrock prefix would never reach NameRules, and a typo would get
                        // Brigadier's parse error instead of Namebook's "invalid" message.
                        .required("query", greedyStringParser())
                        .permission("namebook.lookup")
                        .handler(ctx -> this.lookup(ctx.sender().source(), ctx.<String>get("query")))
                        .build(),
                root.literal("import")
                        .literal("usercache")
                        .permission("namebook.admin")
                        .handler(this::importUserCache)
                        .build(),
                root.literal("reload")
                        .permission("namebook.admin")
                        .handler(this::reload)
                        .build());
    }

    /** Looks {@code query} up as a UUID when it is one, otherwise as a name. Completes after replying. */
    CompletableFuture<Void> lookup(@NotNull CommandSender sender, @NotNull String query) {
        UUID id = parseUuid(query);
        if (id != null) {
            return this.chain.resolveName(id)
                    .thenAccept(found -> this.reply(sender, query, found.value(), found.source()));
        }
        // Checked here, not left to the chain, so a typo reads "invalid" rather than "unknown".
        if (!this.rules.isValid(query)) {
            sender.sendMessage(this.messages.get().messageFor(MessageKeys.LOOKUP_INVALID));
            return CompletableFuture.completedFuture(null);
        }
        return this.chain.resolveUuid(query)
                .thenAccept(found -> this.reply(sender, query, found.value().map(UUID::toString), found.source()));
    }

    private void reply(CommandSender sender, String query, Optional<String> answer, @Nullable SourceId source) {
        Messages text = this.messages.get();
        if (answer.isEmpty()) {
            sender.sendMessage(text.messageFor(MessageKeys.LOOKUP_UNKNOWN));
            return;
        }
        sender.sendMessage(text.messageFor(MessageKeys.LOOKUP_FOUND,
                MessageContainer.value("query", query),
                MessageContainer.value("answer", answer.get()),
                MessageContainer.value("source", describe(source))));
    }

    private void importUserCache(CommandContext<Source> ctx) {
        CommandSender sender = ctx.sender().source();
        CompletableFuture.supplyAsync(() -> {
            try {
                return this.importer.importFile(this.userCache);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }, this.database).whenComplete((result, error) -> this.reportImport(sender, result, error));
    }

    private void reportImport(CommandSender sender, @Nullable ImportResult result, @Nullable Throwable error) {
        Messages text = this.messages.get();
        if (error == null && result != null) {
            sender.sendMessage(text.messageFor(MessageKeys.IMPORT_DONE,
                    MessageContainer.value("added", String.valueOf(result.added())),
                    MessageContainer.value("skipped", String.valueOf(result.skipped()))));
            return;
        }
        Throwable cause = unwrap(error);
        if (cause instanceof PersistenceException) {
            this.log.log(Level.WARNING, "User cache import failed: the database is not reachable", cause);
            sender.sendMessage(text.messageFor(MessageKeys.ERRORS_DATABASE_UNAVAILABLE));
            return;
        }
        String reason = cause instanceof NoSuchFileException
                ? "there is no " + this.userCache.getFileName() + " in the server folder"
                : String.valueOf(cause.getMessage());
        sender.sendMessage(text.messageFor(MessageKeys.IMPORT_FAILED, MessageContainer.value("reason", reason)));
    }

    private void reload(CommandContext<Source> ctx) {
        CommandSender sender = ctx.sender().source();
        this.reloader.get().whenComplete((done, error) -> {
            if (error != null) {
                sender.sendMessage(this.messages.get().messageFor(MessageKeys.RELOAD_FAILED,
                        MessageContainer.value("reason", String.valueOf(unwrap(error).getMessage()))));
                return;
            }
            sender.sendMessage(this.messages.get().messageFor(MessageKeys.RELOAD_DONE));
        });
    }

    /** Strict on purpose: {@link UUID#fromString} also accepts short forms such as {@code 1-1-1-1-1}. */
    private static @Nullable UUID parseUuid(String text) {
        if (DASHED_UUID.matcher(text).matches()) {
            return UUID.fromString(text);
        }
        if (PLAIN_UUID.matcher(text).matches()) {
            return new UUID(Long.parseUnsignedLong(text.substring(0, 16), 16),
                    Long.parseUnsignedLong(text.substring(16), 16));
        }
        return null;
    }

    private static String describe(@Nullable SourceId source) {
        if (source == null) {
            return "unknown";
        }
        return switch (source) {
            case CACHE -> "cache";
            case DATABASE -> "database";
            case SERVER -> "server";
            case PLAYERDB -> "playerdb.co";
            case MOJANG -> "Mojang";
        };
    }

    private static Throwable unwrap(@Nullable Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof UncheckedIOException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause == null ? new IllegalStateException("unknown error") : cause;
    }
}
