package com.minecraftcitiesnetwork.namebook.paper.config;

import java.nio.file.Path;
import java.util.logging.Logger;

final class ConfigTestSupport {

    private ConfigTestSupport() {
    }

    /** Loads the bundled resources exactly as the plugin does. */
    static ConfigLoader loader(Path dataFolder) {
        return new ConfigLoader(dataFolder, ConfigLoader.class.getClassLoader(), Logger.getLogger("test"));
    }
}
