package com.minecraftcitiesnetwork.namebook.paper.command;

import com.minecraftcitiesnetwork.namebook.core.chain.LookupChain;
import com.minecraftcitiesnetwork.namebook.core.importer.UserCacheImporter;
import com.minecraftcitiesnetwork.namebook.core.name.NameRules;
import com.minecraftcitiesnetwork.namebook.core.source.Resolution;
import com.minecraftcitiesnetwork.namebook.core.source.SourceId;
import com.minecraftcitiesnetwork.namebook.paper.config.ConfigLoader;
import com.minecraftcitiesnetwork.namebook.paper.config.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NamebookCommandTest {

    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @TempDir
    Path dir;

    private final LookupChain chain = mock(LookupChain.class);
    private Messages messages;
    private NamebookCommand command;

    @BeforeEach
    void setUp() throws Exception {
        this.messages = new ConfigLoader(this.dir, ConfigLoader.class.getClassLoader(), Logger.getLogger("test"))
                .loadMessages();
        this.command = new NamebookCommand(this.chain, new NameRules(".", true), mock(UserCacheImporter.class),
                this.dir.resolve("usercache.json"), Runnable::run, () -> CompletableFuture.completedFuture(null),
                () -> this.messages, Logger.getLogger("test"));
    }

    @Test
    void lookup_parsesUuidOrName() {
        when(this.chain.resolveName(NOTCH))
                .thenReturn(CompletableFuture.completedFuture(new Resolution<>(Optional.of("Notch"), SourceId.PLAYERDB)));
        when(this.chain.resolveUuid("notch"))
                .thenReturn(CompletableFuture.completedFuture(new Resolution<>(Optional.of(NOTCH), SourceId.DATABASE)));

        assertEquals(List.of("[Namebook] " + NOTCH + " is Notch (playerdb.co)"), this.run(NOTCH.toString()));
        // Without dashes is a UUID too, and is not mistaken for a 32-character name.
        assertEquals(List.of("[Namebook] 069a79f444e94726a5befca90e38aaf5 is Notch (playerdb.co)"),
                this.run("069a79f444e94726a5befca90e38aaf5"));
        assertEquals(List.of("[Namebook] notch is " + NOTCH + " (database)"), this.run("notch"));
    }

    @Test
    void lookup_unknown_showsUnknown() {
        when(this.chain.resolveUuid("Nobody")).thenReturn(CompletableFuture.completedFuture(Resolution.unknown()));

        assertEquals(List.of(this.messages.plaintextMessageFor("lookup.unknown")), this.run("Nobody"));
    }

    @Test
    void lookup_invalidName_showsInvalid() {
        String invalid = this.messages.plaintextMessageFor("lookup.invalid");
        for (String query : List.of("a b", "%", ".", "abcdefghijklmnopq", "1-1-1-1-1")) {
            assertEquals(List.of(invalid), this.run(query), query);
        }
        verifyNoInteractions(this.chain);
    }

    private List<String> run(String query) {
        CommandSender sender = mock(CommandSender.class);
        this.command.lookup(sender, query).join();
        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(sender, org.mockito.Mockito.atLeast(0)).sendMessage(sent.capture());
        return sent.getAllValues().stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
    }
}
