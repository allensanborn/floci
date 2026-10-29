package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An ECS container volume inheritance reference:
 * {@code {"sourceContainer": ..., "readOnly": ...}}.
 * {@code readOnly} is null when the caller did not send it (AWS omits it on describe).
 */
@RegisterForReflection
public record VolumeFrom(String sourceContainer, Boolean readOnly) {
}
