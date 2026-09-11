package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class RedshiftServerlessService implements Resettable {
    public static final String DEFAULT_DB_NAME = "dev";
    public static final String AWS_OWNED_KMS_KEY = "AWS_OWNED_KMS_KEY";

    private static final Pattern NAMESPACE_NAME = Pattern.compile("[a-z0-9-]+");
    private static final Pattern DB_NAME = Pattern.compile("[a-zA-Z][a-zA-Z_0-9+.@-]*");
    private static final Set<String> LOG_EXPORTS = Set.of("useractivitylog", "userlog", "connectionlog");

    private final AccountAwareStorageBackend<Namespace> namespaces;
    private final RegionResolver regionResolver;

    @Inject
    public RedshiftServerlessService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.namespaces = storageFactory.create("redshiftserverless", "redshiftserverless-namespaces.json",
                new TypeReference<Map<String, Namespace>>() {});
        this.regionResolver = regionResolver;
    }

    public synchronized Namespace createNamespace(String namespaceName, String adminUsername, String dbName,
                                                  String kmsKeyId, String defaultIamRoleArn, List<String> iamRoles,
                                                  List<String> logExports, Map<String, String> tags, String region) {
        validateNamespaceName(namespaceName);
        String key = storageKey(region, namespaceName);
        if (namespaces.get(key).isPresent()) {
            throw new AwsException("ConflictException",
                    "The namespace " + namespaceName + " already exists.", 409);
        }

        Namespace namespace = new Namespace();
        namespace.setNamespaceName(namespaceName);
        namespace.setNamespaceId(UUID.randomUUID().toString());
        namespace.setNamespaceArn(regionResolver.buildArn("redshift-serverless", region,
                "namespace/" + namespace.getNamespaceId()));
        namespace.setAdminUsername(adminUsername);
        namespace.setDbName(validateDbName(dbName));
        namespace.setKmsKeyId(kmsKeyId == null || kmsKeyId.isBlank() ? AWS_OWNED_KMS_KEY : kmsKeyId);
        namespace.setDefaultIamRoleArn(defaultIamRoleArn);
        namespace.setIamRoles(copyOf(iamRoles));
        namespace.setLogExports(validateLogExports(logExports));
        namespace.setStatus("AVAILABLE");
        namespace.setCreationDate(Instant.now());
        namespace.setTags(tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags));
        namespaces.put(key, namespace);
        return namespace;
    }

    public Namespace getNamespace(String namespaceName, String region) {
        validateNamespaceName(namespaceName);
        return namespaces.get(storageKey(region, namespaceName))
                .orElseThrow(() -> notFound(namespaceName));
    }

    public PaginatedResult<Namespace> listNamespaces(String region, Integer maxResults, String nextToken) {
        List<Namespace> all = namespaces.scan(key -> key.startsWith(region + "::"));
        return Pagination.paginate(all, Namespace::getNamespaceName, maxResults, nextToken,
                100, 100, "ValidationException");
    }

    public synchronized Namespace updateNamespace(String namespaceName, String adminUsername, String kmsKeyId,
                                                  String defaultIamRoleArn, List<String> iamRoles,
                                                  List<String> logExports, String region) {
        String key = storageKey(region, namespaceName);
        Namespace namespace = getNamespace(namespaceName, region);
        if (adminUsername != null) {
            namespace.setAdminUsername(adminUsername);
        }
        if (kmsKeyId != null && !kmsKeyId.isBlank()) {
            namespace.setKmsKeyId(kmsKeyId);
        }
        if (defaultIamRoleArn != null) {
            namespace.setDefaultIamRoleArn(defaultIamRoleArn);
        }
        if (iamRoles != null) {
            namespace.setIamRoles(copyOf(iamRoles));
        }
        if (logExports != null) {
            namespace.setLogExports(validateLogExports(logExports));
        }
        namespaces.put(key, namespace);
        return namespace;
    }

    public synchronized Namespace deleteNamespace(String namespaceName, String region) {
        Namespace namespace = getNamespace(namespaceName, region);
        namespaces.delete(storageKey(region, namespaceName));
        namespace.setStatus("DELETING");
        return namespace;
    }

    public Map<String, String> listTagsForResource(String resourceArn, String region) {
        return new LinkedHashMap<>(resolveByArn(resourceArn, region).getTags());
    }

    public synchronized Map<String, String> tagResource(String resourceArn, Map<String, String> tags, String region) {
        Namespace namespace = resolveByArn(resourceArn, region);
        if (tags != null) {
            namespace.getTags().putAll(tags);
        }
        namespaces.put(storageKey(region, namespace.getNamespaceName()), namespace);
        return new LinkedHashMap<>(namespace.getTags());
    }

    public synchronized Map<String, String> untagResource(String resourceArn, List<String> tagKeys, String region) {
        Namespace namespace = resolveByArn(resourceArn, region);
        if (tagKeys == null) {
            throw validation("tagKeys is required.");
        }
        tagKeys.forEach(namespace.getTags()::remove);
        namespaces.put(storageKey(region, namespace.getNamespaceName()), namespace);
        return new LinkedHashMap<>(namespace.getTags());
    }

    /**
     * Redshift Serverless tags whatever the ARN names, so the lookup is by ARN rather than by
     * namespace name. Only namespaces are taggable in Floci, so any other Redshift Serverless ARN
     * resolves to nothing and is reported as absent rather than as an unsupported resource type.
     */
    private Namespace resolveByArn(String resourceArn, String region) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw validation("resourceArn is required.");
        }
        return namespaces.scan(key -> key.startsWith(region + "::")).stream()
                .filter(namespace -> resourceArn.equals(namespace.getNamespaceArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The resource " + resourceArn + " was not found.", 404));
    }

    @Override
    public void clear() {
        namespaces.clear();
    }

    private static void validateNamespaceName(String namespaceName) {
        if (namespaceName == null || namespaceName.length() < 3 || namespaceName.length() > 64
                || !NAMESPACE_NAME.matcher(namespaceName).matches()) {
            throw validation("namespaceName must be 3-64 characters of lowercase letters, numbers, and hyphens.");
        }
    }

    private static String validateDbName(String dbName) {
        if (dbName == null || dbName.isBlank()) {
            return DEFAULT_DB_NAME;
        }
        if (dbName.length() > 127 || !DB_NAME.matcher(dbName).matches()) {
            throw validation("dbName must start with a letter and be at most 127 characters.");
        }
        return dbName;
    }

    private static List<String> validateLogExports(List<String> logExports) {
        List<String> validated = copyOf(logExports);
        for (String logExport : validated) {
            if (!LOG_EXPORTS.contains(logExport)) {
                throw validation("logExports must contain only useractivitylog, userlog, and connectionlog.");
            }
        }
        return validated;
    }

    private static List<String> copyOf(List<String> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    private static String storageKey(String region, String namespaceName) {
        return region + "::" + namespaceName;
    }

    private static AwsException notFound(String namespaceName) {
        return new AwsException("ResourceNotFoundException",
                "The namespace " + namespaceName + " was not found.", 404);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
