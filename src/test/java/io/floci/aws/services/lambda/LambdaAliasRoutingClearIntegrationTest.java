package io.floci.aws.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * UpdateAlias with an empty {@code AdditionalVersionWeights} clears the alias's routing weights,
 * which is how the Terraform AWS provider removes {@code routing_config}. An UpdateAlias that
 * omits {@code RoutingConfig} leaves them alone.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaAliasRoutingClearIntegrationTest {

    private static final String FN = "alias-routing-clear-fn";
    private static final String ALIASES = "/2015-03-31/functions/" + FN + "/aliases";

    private static String zipBase64() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("handler.py"));
            zos.write("def handler(e, c):\n    return 1\n".getBytes());
            zos.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static void update(String body) {
        given().contentType("application/json").body(body)
        .when().put(ALIASES + "/live")
        .then().statusCode(200);
    }

    @Test
    @Order(1)
    void createAliasWithWeights() throws Exception {
        given()
            .contentType("application/json")
            .body("""
                {"FunctionName": "%s", "Runtime": "python3.12",
                 "Role": "arn:aws:iam::000000000000:role/lambda-role",
                 "Handler": "handler.handler", "Code": {"ZipFile": "%s"}}
                """.formatted(FN, zipBase64()))
        .when().post("/2015-03-31/functions").then().statusCode(201);
        given().contentType("application/json").body("{}")
        .when().post("/2015-03-31/functions/" + FN + "/versions").then().statusCode(201);
        given().contentType("application/json").body("{}")
        .when().post("/2015-03-31/functions/" + FN + "/versions").then().statusCode(201);

        given()
            .contentType("application/json")
            .body("""
                {"Name": "live", "FunctionVersion": "1",
                 "RoutingConfig": {"AdditionalVersionWeights": {"2": 0.25}}}
                """)
        .when().post(ALIASES).then().statusCode(201);
    }

    @Test
    @Order(2)
    void updateWithoutRoutingConfigKeepsWeights() {
        update("{\"Description\": \"d\"}");

        given().when().get(ALIASES + "/live").then().statusCode(200)
            .body("RoutingConfig.AdditionalVersionWeights.2", equalTo(0.25f));
    }

    @Test
    @Order(3)
    void updateWithEmptyWeightsClearsThem() {
        update("{\"RoutingConfig\": {\"AdditionalVersionWeights\": {}}}");

        given().when().get(ALIASES + "/live").then().statusCode(200)
            .body("RoutingConfig", nullValue());
    }

    @Test
    @Order(4)
    void updateWithRoutingConfigAndNoWeightsClearsThem() {
        // aws-sdk-go-v2 skips a nil map, so the Terraform provider's clear arrives as {"RoutingConfig":{}}.
        assertClears("{\"RoutingConfig\": {}}");
    }

    @Test
    @Order(5)
    void updateWithNullWeightsClearsThem() {
        assertClears("{\"RoutingConfig\": {\"AdditionalVersionWeights\": null}}");
    }

    private static void assertClears(String clearBody) {
        update("{\"RoutingConfig\": {\"AdditionalVersionWeights\": {\"2\": 0.5}}}");
        given().when().get(ALIASES + "/live").then().statusCode(200)
            .body("RoutingConfig.AdditionalVersionWeights.2", equalTo(0.5f));

        update(clearBody);

        given().when().get(ALIASES + "/live").then().statusCode(200)
            .body("RoutingConfig", nullValue());
    }
}
