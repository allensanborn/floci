package io.floci.aws.services.amazonmq;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.amazonmq.model.MqConfiguration;
import io.floci.aws.services.amazonmq.model.MqConfiguration.Revision;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Amazon MQ configurations (CreateConfiguration and friends). Metadata only; no broker consumes them. */
@ApplicationScoped
public class AmazonMqConfigurationService {

    private static final Set<String> ENGINES = Set.of("ACTIVEMQ", "RABBITMQ");
    private static final String DEFAULT_ACTIVEMQ_VERSION = "5.17.6";
    static final int DEFAULT_PAGE = 20;
    static final int MAX_PAGE = 100;

    private final Object lock = new Object();

    private final StorageBackend<String, MqConfiguration> storage;
    private final RegionResolver regionResolver;

    @Inject
    public AmazonMqConfigurationService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.storage = storageFactory.create("amazonmq", "amazonmq-configurations.json",
                new TypeReference<Map<String, MqConfiguration>>() {});
        this.regionResolver = regionResolver;
    }

    public MqConfiguration create(String name, String engineType, String engineVersion,
                                  String authenticationStrategy, Map<String, String> tags) {
        if (name == null || !name.matches("[A-Za-z0-9._~-]{1,150}")) {
            throw new AwsException("BadRequestException",
                    "Configuration name must be 1-150 characters of letters, digits, '.', '_', '~' or '-'.", 400);
        }
        if (engineType == null || !ENGINES.contains(engineType.toUpperCase())) {
            throw new AwsException("BadRequestException", "EngineType must be ACTIVEMQ or RABBITMQ.", 400);
        }
        String type = engineType.toUpperCase();
        // AWS defaults EngineVersion to the latest for the engine; these are fixed stand-ins.
        String version = engineVersion != null && !engineVersion.isBlank() ? engineVersion
                : "ACTIVEMQ".equals(type) ? DEFAULT_ACTIVEMQ_VERSION : AmazonMqService.DEFAULT_ENGINE_VERSION;
        synchronized (lock) {
            if (inRegion().stream().anyMatch(c -> c.name.equals(name))) {
                throw new AwsException("ConflictException", "Configuration already exists: " + name, 409);
            }
            MqConfiguration c = new MqConfiguration();
            c.id = "c-" + UUID.randomUUID();
            c.arn = AwsArnUtils.Arn.of("mq", regionResolver.getRegion(), regionResolver.getAccountId(),
                    "configuration:" + c.id).toString();
            c.name = name;
            c.engineType = type;
            c.engineVersion = version;
            // Configuration.AuthenticationStrategy is required in the model; SIMPLE is the default.
            c.authenticationStrategy = authenticationStrategy == null ? "SIMPLE" : authenticationStrategy;
            c.created = Instant.now();
            if (tags != null) {
                c.tags.putAll(tags);
            }
            // AWS seeds revision 1 with a default configuration for the engine.
            c.revisions.add(revision(1, "Auto-generated default for " + name + " on " + type + " "
                    + version, defaultData(type)));
            storage.put(c.id, c);
            return c;
        }
    }

    public MqConfiguration describe(String id) {
        return storage.get(id).filter(this::isCurrentRegion).orElseThrow(() -> new AwsException("NotFoundException",
                "Configuration not found: " + id, 404));
    }

    public PaginatedResult<MqConfiguration> list(Integer maxResults, String nextToken) {
        return Pagination.paginate(inRegion(), c -> c.id, maxResults, nextToken, DEFAULT_PAGE, MAX_PAGE,
                "BadRequestException");
    }

    public MqConfiguration update(String id, String data, String description) {
        if (data == null || data.isBlank()) {
            throw new AwsException("BadRequestException", "Data is required.", 400);
        }
        try {
            Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new AwsException("BadRequestException", "Data must be base64 encoded.", 400);
        }
        synchronized (lock) {
            MqConfiguration c = describe(id);
            c.revisions.add(revision(c.latest().revision + 1, description, data));
            storage.put(id, c);
            return c;
        }
    }

    public void delete(String id) {
        synchronized (lock) {
            describe(id);
            storage.delete(id);
        }
    }

    public Revision revision(String id, int revision) {
        return describe(id).revisions.stream().filter(r -> r.revision == revision).findFirst()
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Revision " + revision + " not found for configuration " + id, 404));
    }

    public PaginatedResult<Revision> revisions(String id, Integer maxResults, String nextToken) {
        List<Revision> all;
        synchronized (lock) {
            all = List.copyOf(describe(id).revisions);
        }
        return Pagination.paginate(all, r -> String.valueOf(r.revision), maxResults, nextToken,
                DEFAULT_PAGE, MAX_PAGE, "BadRequestException");
    }

    // Tag endpoints address a configuration by ARN. Reads return a copy, never the stored map.
    public Map<String, String> listTags(String arn) {
        synchronized (lock) {
            return new LinkedHashMap<>(byArn(arn).tags);
        }
    }

    public void tag(String arn, Map<String, String> tags) {
        synchronized (lock) {
            MqConfiguration c = byArn(arn);
            c.tags.putAll(tags);
            storage.put(c.id, c);
        }
    }

    public void untag(String arn, List<String> tagKeys) {
        synchronized (lock) {
            MqConfiguration c = byArn(arn);
            tagKeys.forEach(c.tags::remove);
            storage.put(c.id, c);
        }
    }

    private MqConfiguration byArn(String arn) {
        return inRegion().stream().filter(c -> c.arn.equals(arn)).findFirst()
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Resource not found: " + arn, 404));
    }

    // The region lives in the ARN, as MskService does for its configurations.
    private boolean isCurrentRegion(MqConfiguration c) {
        return regionResolver.getRegion().equals(AwsArnUtils.regionOrDefault(c.arn, null));
    }

    private List<MqConfiguration> inRegion() {
        return storage.scan(k -> true).stream().filter(this::isCurrentRegion).toList();
    }

    private static Revision revision(int number, String description, String data) {
        Revision r = new Revision();
        r.revision = number;
        r.created = Instant.now();
        r.description = description;
        r.data = data;
        return r;
    }

    private static String defaultData(String engineType) {
        String body = "ACTIVEMQ".equals(engineType)
                ? "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                  + "<broker xmlns=\"http://activemq.apache.org/schema/core\"/>\n"
                : "# Default RabbitMQ configuration\n";
        return Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
    }
}
