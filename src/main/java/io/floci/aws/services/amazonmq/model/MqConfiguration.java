package io.floci.aws.services.amazonmq.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An Amazon MQ configuration: a named, versioned blob of broker settings. Every
 * UpdateConfiguration appends a {@link Revision}; the last one is the latest.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MqConfiguration {

    public String id;
    public String arn;
    public String name;
    public String engineType;
    public String engineVersion;
    public String authenticationStrategy;
    public Instant created;
    public Map<String, String> tags = new LinkedHashMap<>();
    public List<Revision> revisions = new ArrayList<>();

    public Revision latest() {
        return revisions.get(revisions.size() - 1);
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Revision {
        public int revision;
        public Instant created;
        public String description;
        /** Base64, exactly as the caller sent it. */
        public String data;
    }
}
