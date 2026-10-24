package com.minecraftcitiesnetwork.namebook.paper.source;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.minecraftcitiesnetwork.namebook.core.source.SourceId;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerCacheSourceTest {

    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    /** A profile for {@code id} that the user cache completes to {@code name}, or not at all when null. */
    private static PlayerProfile userCache(Server server, UUID id, String name) {
        PlayerProfile profile = mock(PlayerProfile.class);
        when(profile.completeFromCache()).thenReturn(name != null);
        when(profile.getName()).thenReturn(name);
        when(server.createProfile(id)).thenReturn(profile);
        return profile;
    }

    /** A profile for {@code name} that Paper's profile cache completes to {@code id}, or not at all when null. */
    private static PlayerProfile profileCache(Server server, String name, UUID id) {
        PlayerProfile profile = mock(PlayerProfile.class);
        when(profile.completeFromCache(false, true)).thenReturn(id != null);
        when(profile.getId()).thenReturn(id);
        when(server.createProfile(name)).thenReturn(profile);
        return profile;
    }

    @Test
    void serverSource_readsProfileCachesOnly() {
        Server server = mock(Server.class);
        userCache(server, NOTCH, "Notch");
        PlayerProfile notch = profileCache(server, "Notch", NOTCH);
        profileCache(server, "Unknown", null);
        ServerCacheSource source = new ServerCacheSource(server, Runnable::run);

        assertEquals(SourceId.SERVER, source.id());
        assertEquals(Optional.of("Notch"), source.nameOf(NOTCH).join());
        assertEquals(Optional.of(NOTCH), source.uuidOf("Notch").join());
        assertEquals(Optional.empty(), source.uuidOf("Unknown").join());
        // Only PlayerProfile is used. getOfflinePlayer(String) may go to Mojang over the network,
        // OfflinePlayer#getName() loads the playerdata file of a player missing from the user cache,
        // and getOfflinePlayerIfCached reads the online players, which only the main thread may do.
        verify(server, never()).getOfflinePlayer(anyString());
        verify(server, never()).getOfflinePlayer(any(UUID.class));
        verify(server, never()).getOfflinePlayerIfCached(anyString());
        // completeFromCache(true, ...) falls back to a blocking Mojang lookup for an unknown name.
        verify(notch, never()).completeFromCache(eq(true), anyBoolean());
    }

    @Test
    void serverSource_unknownUuid_isEmpty() {
        Server server = mock(Server.class);
        userCache(server, NOTCH, null);
        ServerCacheSource source = new ServerCacheSource(server, Runnable::run);

        assertEquals(Optional.empty(), source.nameOf(NOTCH).join());
        verify(server, never()).getOfflinePlayer(any(UUID.class));
    }

    @Test
    void serverSource_serverThrows_isEmpty() {
        Server server = mock(Server.class);
        when(server.createProfile(NOTCH)).thenThrow(new IllegalStateException("boom"));
        when(server.createProfile("Notch")).thenThrow(new IllegalStateException("boom"));
        ServerCacheSource source = new ServerCacheSource(server, Runnable::run);

        assertEquals(Optional.empty(), source.nameOf(NOTCH).join());
        assertEquals(Optional.empty(), source.uuidOf("Notch").join());
    }
}
