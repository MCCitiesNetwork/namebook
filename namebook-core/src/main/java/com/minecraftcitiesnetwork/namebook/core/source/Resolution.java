package com.minecraftcitiesnetwork.namebook.core.source;

import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/** A lookup result and the source that produced it; the source is null when nothing knew the answer. */
public record Resolution<T>(Optional<T> value, @Nullable SourceId source) {

    public static <T> Resolution<T> unknown() {
        return new Resolution<>(Optional.empty(), null);
    }
}
