package com.minecraftcitiesnetwork.namebook.paper.config;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;

/**
 * Every player-facing string, loaded from {@code messages.yml}. Runtime values go in through
 * {@link MessageContainer#value(String, String)} so player text is never parsed as markup.
 */
public final class Messages extends MessageContainer {
}
