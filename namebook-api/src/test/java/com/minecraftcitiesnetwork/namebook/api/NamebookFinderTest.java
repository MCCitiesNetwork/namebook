package com.minecraftcitiesnetwork.namebook.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

class NamebookFinderTest {

    @Test
    void find_returnsRegisteredService() {
        ServicesManager services = mock(ServicesManager.class);
        Namebook namebook = mock(Namebook.class);
        when(services.load(Namebook.class)).thenReturn(namebook);

        assertEquals(Optional.of(namebook), NamebookFinder.find(services));
    }

    @Test
    void find_emptyWhenNotRegistered() {
        ServicesManager services = mock(ServicesManager.class);
        when(services.load(Namebook.class)).thenReturn(null);

        assertTrue(NamebookFinder.find(services).isEmpty());
    }
}
