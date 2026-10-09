package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ListVersionsByFunction returns {@code $LATEST} first, then published versions in ascending
 * numeric order. The Terraform AWS provider reads the last entry as the function's current
 * version, so storage-scan order leaks out as a wrong {@code version} attribute.
 */
class LambdaFunctionStoreListVersionsTest {

    private static final String REGION = "us-east-1";
    private static final String NAME = "ordered-fn";

    @Test
    void listVersionsReturnsLatestFirstThenNumericAscending() {
        InMemoryStorage<String, LambdaFunction> backend = new InMemoryStorage<>();
        LambdaFunctionStore store = new LambdaFunctionStore(backend);
        List<String> expected = new ArrayList<>(List.of("$LATEST"));
        for (int i = 1; i <= 12; i++) {
            expected.add(String.valueOf(i));
        }
        // Insert in reverse so neither insertion order nor hash order can pass by accident.
        for (int i = expected.size() - 1; i >= 0; i--) {
            store.save(REGION, version(expected.get(i)));
        }
        store.save("eu-west-1", version("99"));

        List<String> raw = backend.scan(k -> k.startsWith("lambda::" + REGION + "::" + NAME + "::"))
                .stream().map(LambdaFunction::getVersion).toList();
        assertNotEquals(expected, raw, "precondition: the raw scan must be out of order");

        List<String> actual = store.listVersions(REGION, NAME).stream()
                .map(LambdaFunction::getVersion).toList();
        assertEquals(expected, actual);
    }

    private static LambdaFunction version(String version) {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName(NAME);
        fn.setVersion(version);
        return fn;
    }
}
