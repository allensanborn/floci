package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedshiftServerlessServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "123456789012";

    private RedshiftServerlessService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        AccountAwareStorageBackend<Namespace> store = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        when(storageFactory.create(eq("redshiftserverless"), eq("redshiftserverless-namespaces.json"),
                any(TypeReference.class))).thenReturn((AccountAwareStorageBackend) store);

        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.buildArn(eq("redshift-serverless"), eq(REGION), any(String.class)))
                .thenAnswer(invocation -> "arn:aws:redshift-serverless:" + REGION + ":" + ACCOUNT_ID + ":"
                        + invocation.getArgument(2, String.class));
        service = new RedshiftServerlessService(storageFactory, regionResolver);
    }

    @Test
    void createAppliesAwsDefaultsAndAUniqueNamespaceId() {
        Namespace first = create("first-ns");
        Namespace second = create("second-ns");

        assertEquals("dev", first.getDbName());
        assertEquals("AWS_OWNED_KMS_KEY", first.getKmsKeyId());
        assertEquals("AVAILABLE", first.getStatus());
        assertTrue(first.getLogExports().isEmpty());
        assertEquals("arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":namespace/" + first.getNamespaceId(),
                first.getNamespaceArn());
        assertEquals(first.getNamespaceId(), UUID.fromString(first.getNamespaceId()).toString());
        assertNotEquals(first.getNamespaceId(), second.getNamespaceId());
    }

    @Test
    void createRejectsADuplicateNamespaceName() {
        create("duplicate-ns");
        AwsException conflict = assertThrows(AwsException.class, () -> create("duplicate-ns"));
        assertEquals("ConflictException", conflict.getErrorCode());
    }

    @Test
    void getAndDeleteRejectAnUnknownNamespace() {
        assertEquals("ResourceNotFoundException",
                assertThrows(AwsException.class, () -> service.getNamespace("absent-ns", REGION)).getErrorCode());
        assertEquals("ResourceNotFoundException",
                assertThrows(AwsException.class, () -> service.deleteNamespace("absent-ns", REGION)).getErrorCode());
    }

    @Test
    void updatePersistsOnlyTheSuppliedFields() {
        service.createNamespace("update-ns", "admin", "analytics", null, null,
                List.of("arn:aws:iam::123456789012:role/one"), List.of("userlog"), Map.of(), REGION);

        service.updateNamespace("update-ns", null, "custom-key", null, List.of(), null, REGION);

        Namespace reread = service.getNamespace("update-ns", REGION);
        assertEquals("custom-key", reread.getKmsKeyId());
        assertEquals("admin", reread.getAdminUsername());
        assertEquals("analytics", reread.getDbName());
        assertEquals(List.of("userlog"), reread.getLogExports());
        assertTrue(reread.getIamRoles().isEmpty());
    }

    @Test
    void deleteReportsDeletingAndRemovesTheNamespace() {
        create("delete-ns");
        assertEquals("DELETING", service.deleteNamespace("delete-ns", REGION).getStatus());
        assertThrows(AwsException.class, () -> service.getNamespace("delete-ns", REGION));
    }

    @Test
    void invalidNamespaceNamesAndLogExportsAreRejected() {
        assertEquals("ValidationException",
                assertThrows(AwsException.class, () -> create("Upper-Case")).getErrorCode());
        assertEquals("ValidationException",
                assertThrows(AwsException.class, () -> create("ab")).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.createNamespace("logs-ns", "admin", null, null, null, null,
                        List.of("nosuchlog"), Map.of(), REGION)).getErrorCode());
    }

    @Test
    void listIsScopedToTheRegionAndPaginates() {
        create("alpha-ns");
        create("beta-ns");
        service.createNamespace("gamma-ns", "admin", null, null, null, null, null, Map.of(), "us-west-2");

        assertEquals(List.of("alpha-ns", "beta-ns"),
                service.listNamespaces(REGION, null, null).items().stream()
                        .map(Namespace::getNamespaceName).toList());

        var firstPage = service.listNamespaces(REGION, 1, null);
        assertEquals(List.of("alpha-ns"), firstPage.items().stream().map(Namespace::getNamespaceName).toList());
        assertEquals(List.of("beta-ns"), service.listNamespaces(REGION, 1, firstPage.nextToken()).items().stream()
                .map(Namespace::getNamespaceName).toList());
    }

    @Test
    void clearRemovesPersistedState() {
        create("reset-ns");
        service.clear();
        assertTrue(service.listNamespaces(REGION, null, null).items().isEmpty());
    }

    private Namespace create(String namespaceName) {
        return service.createNamespace(namespaceName, "admin", null, null, null, null, null, Map.of(), REGION);
    }
}
