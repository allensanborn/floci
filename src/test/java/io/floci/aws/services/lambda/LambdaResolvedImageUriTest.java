package io.floci.aws.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ecr.EcrService;
import io.floci.aws.services.ecr.EcrService.DescribeImagesResult;
import io.floci.aws.services.ecr.model.ImageDetail;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.services.lambda.zip.CodeStore;
import io.floci.aws.services.lambda.zip.ZipExtractor;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Code.ResolvedImageUri pins an image function's repository to the digest its tag had at deployment. */
class LambdaResolvedImageUriTest {

    private static final String REGION = "us-east-1";
    private static final String REPO_URI = "000000000000.dkr.ecr.us-east-1.localhost:4566/app";
    private static final String DIGEST = "sha256:" + "ab".repeat(32);

    private LambdaService service;
    private EcrService ecr;
    private LambdaController controller;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
        service = new LambdaService(new LambdaFunctionStore(new InMemoryStorage<String, LambdaFunction>()),
                new WarmPool(), new CodeStore(Path.of("target/test-data/lambda-code")), new ZipExtractor(), null,
                regionResolver, storageFactory);
        ecr = mock(EcrService.class);
        service.ecrService = ecr;
        controller = new LambdaController(service, regionResolver, new ObjectMapper(), null);
    }

    private void imageHasDigest(String digest) {
        ImageDetail detail = new ImageDetail();
        detail.setImageDigest(digest);
        when(ecr.describeImages(anyString(), any(), anyString(), anyString()))
                .thenReturn(new DescribeImagesResult(List.of(detail), List.of()));
    }

    private Map<String, Object> imageRequest(String uri) {
        return new HashMap<>(Map.of("FunctionName", "img-fn", "PackageType", "Image",
                "Role", "arn:aws:iam::000000000000:role/r", "Code", new HashMap<>(Map.of("ImageUri", uri))));
    }

    private JsonNode getFunctionCode() throws Exception {
        Response response = controller.getFunction(mock(HttpHeaders.class), mock(UriInfo.class), "img-fn", null);
        return new ObjectMapper().valueToTree(response.getEntity()).get("Code");
    }

    @Test
    void createPinsTheRepositoryToTheTagsDigestAndKeepsImageUriAsGiven() throws Exception {
        imageHasDigest(DIGEST);
        service.createFunction(REGION, imageRequest(REPO_URI + ":v1"));

        JsonNode code = getFunctionCode();
        assertEquals(REPO_URI + ":v1", code.get("ImageUri").asText());
        assertEquals(REPO_URI + "@" + DIGEST, code.get("ResolvedImageUri").asText());
    }

    @Test
    void updateFunctionCodeRepinsToTheNewImagesDigest() throws Exception {
        imageHasDigest(DIGEST);
        service.createFunction(REGION, imageRequest(REPO_URI + ":v1"));
        String other = "sha256:" + "cd".repeat(32);
        imageHasDigest(other);
        service.updateFunctionCode(REGION, "img-fn", new HashMap<>(Map.of("ImageUri", REPO_URI + ":v2")));

        assertEquals(REPO_URI + "@" + other, getFunctionCode().get("ResolvedImageUri").asText());
    }

    @Test
    void unreadableImageKeepsTheUriAsGiven() throws Exception {
        when(ecr.describeImages(anyString(), any(), anyString(), anyString()))
                .thenThrow(new AwsException("ServerException", "no docker", 500));
        LambdaFunction fn = service.createFunction(REGION, imageRequest(REPO_URI + ":v1"));

        assertNull(fn.getResolvedImageUri());
        assertEquals(REPO_URI + ":v1", getFunctionCode().get("ResolvedImageUri").asText());
    }
}
