package com.minecraftcitiesnetwork.namebook.paper.config;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.ComponentSerializer;
import com.minecraftcitiesnetwork.pluginInfrastructure.util.DurationParserUtil;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Loads the plugin's YAML files. A missing live file is copied from the bundled resource, the
 * bundled defaults are merged underneath whatever the admin wrote (so new keys appear after an
 * upgrade), and a pristine, commented copy is refreshed under {@code defaults/}. The live file is
 * rewritten only when keys were added, because the rewrite loses its comments.
 */
public final class ConfigLoader {

    /** Zero in any unit; {@code DurationParserUtil} rejects it but a repeat of {@code 0s} is meaningful. */
    private static final Pattern ZERO_DURATION =
            Pattern.compile("(?i)(0+(s|m|min|h|hr|d|w|wk))+");

    private final Path dataFolder;
    private final ClassLoader resources;
    private final Logger log;

    public ConfigLoader(Path dataFolder, ClassLoader resources, Logger log) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.log = Objects.requireNonNull(log, "log");
    }

    /** Loads {@code fileName} (for example {@code settings.yml}) into {@code type}. */
    public <T> T load(String fileName, Class<T> type) throws ConfigurateException {
        ConfigurationNode root = loadNode(fileName);
        T value = root.get(type);
        if (value == null) {
            throw new ConfigurateException(root, "Could not read " + fileName + " as " + type.getSimpleName());
        }
        return value;
    }

    /** Loads {@code messages.yml} into a fresh {@link Messages}. */
    public Messages loadMessages() throws ConfigurateException {
        Messages messages = new Messages();
        messages.load(loadNode("messages.yml"));
        return messages;
    }

    private ConfigurationNode loadNode(String fileName) throws ConfigurateException {
        try {
            Files.createDirectories(this.dataFolder.resolve("defaults"));
            String baseName = fileName.endsWith(".yml")
                    ? fileName.substring(0, fileName.length() - 4) : fileName;
            // The pristine copy is always refreshed so admins can diff against the shipped file.
            copyResource(fileName, this.dataFolder.resolve("defaults").resolve("default-" + baseName + ".yml"), true);
            Path live = this.dataFolder.resolve(fileName);
            copyResource(fileName, live, false);

            YamlConfigurationLoader liveLoader = yamlLoader().path(live).build();
            ConfigurationNode root = liveLoader.load();
            try (InputStream in = this.resources.getResourceAsStream(fileName)) {
                if (in != null) {
                    ConfigurationNode defaults = yamlLoader()
                            .source(() -> new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
                            .build().load();
                    // mergeFrom treats an empty string as "not set" and overwrites it with the
                    // default, which would undo a deliberately empty value such as bedrock.prefix.
                    List<Object[]> emptied = new ArrayList<>();
                    for (ConfigurationNode leaf : root.childrenMap().values()) {
                        collectEmptyStrings(leaf, emptied);
                    }
                    boolean missingKeys = hasMissingKeys(defaults, root);
                    root.mergeFrom(defaults);
                    for (Object[] path : emptied) {
                        root.node(path).raw("");
                    }
                    // Configurate drops comments when it reads YAML, so saving rewrites the
                    // admin's file without them. Only do that when there are keys to add.
                    if (missingKeys) {
                        this.log.info("Added missing keys to " + fileName + "; its comments are kept in defaults/.");
                        liveLoader.save(root);
                    }
                }
            }
            return root;
        } catch (IOException ex) {
            throw new ConfigurateException(ex);
        }
    }

    /** Whether {@code defaults} has a key, at any depth, that {@code live} does not. */
    private static boolean hasMissingKeys(ConfigurationNode defaults, ConfigurationNode live) {
        for (var entry : defaults.childrenMap().entrySet()) {
            ConfigurationNode liveChild = live.node(entry.getKey());
            if (liveChild.virtual()) {
                return true;
            }
            if (entry.getValue().isMap() && liveChild.isMap() && hasMissingKeys(entry.getValue(), liveChild)) {
                return true;
            }
        }
        return false;
    }

    private static void collectEmptyStrings(ConfigurationNode node, List<Object[]> out) {
        if (node.isMap()) {
            for (ConfigurationNode child : node.childrenMap().values()) {
                collectEmptyStrings(child, out);
            }
        } else if (node.raw() instanceof String text && text.isEmpty()) {
            out.add(node.path().array());
        }
    }

    private void copyResource(String resourceName, Path target, boolean overwrite) throws IOException {
        if (!overwrite && Files.exists(target)) {
            return;
        }
        try (InputStream in = this.resources.getResourceAsStream(resourceName)) {
            if (in == null) {
                this.log.severe("Bundled resource missing: " + resourceName);
                return;
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private YamlConfigurationLoader.Builder yamlLoader() {
        return YamlConfigurationLoader.builder()
                .defaultOptions(options -> options.serializers(builder -> builder
                        .register(Component.class, ComponentSerializer.MINI_MESSAGE)
                        .register(Duration.class, new DurationSerializer(this.log))))
                .nodeStyle(NodeStyle.BLOCK);
    }

    /**
     * Reads durations written as {@code 3s}, {@code 24h}, {@code 1d12h}. An unreadable value
     * becomes {@code null} so the record's compact constructor substitutes its default, and the
     * mistake is logged instead of stopping the plugin.
     */
    private static final class DurationSerializer implements TypeSerializer<Duration> {

        private final Logger log;

        DurationSerializer(Logger log) {
            this.log = log;
        }

        @Override
        public @Nullable Duration deserialize(Type type, ConfigurationNode node) throws SerializationException {
            String text = node.getString();
            if (text == null) {
                return null;
            }
            if (ZERO_DURATION.matcher(text).matches()) {
                return Duration.ZERO;
            }
            try {
                return DurationParserUtil.parse(text);
            } catch (IllegalArgumentException ex) {
                this.log.warning("Ignoring invalid duration '" + text + "' at "
                        + node.path() + ": " + ex.getMessage() + ". Using the default.");
                return null;
            }
        }

        @Override
        public void serialize(Type type, @Nullable Duration obj, ConfigurationNode node) throws SerializationException {
            if (obj == null) {
                node.raw(null);
                return;
            }
            long seconds = obj.toSeconds();
            if (seconds != 0 && seconds % 3600 == 0) {
                node.set(seconds / 3600 + "h");
            } else if (seconds != 0 && seconds % 60 == 0) {
                node.set(seconds / 60 + "m");
            } else {
                node.set(seconds + "s");
            }
        }
    }
}
