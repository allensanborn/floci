package io.floci.aws.services.amazonmq;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Amazon MQ and MSK both serve {@code /v1/configurations}; the SigV4 signing service decides which one answers.
 */
@QuarkusTest
class AmazonMqConfigurationIntegrationTest {

    private static RequestSpecification signedFor(String service) {
        return signedFor(service, "us-east-1");
    }

    private static RequestSpecification signedFor(String service, String region) {
        return given()
                .contentType("application/json")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/" + region + "/" + service
                        + "/aws4_request, SignedHeaders=host, Signature=x");
    }

    @Test
    void configurationsAreScopedByRegion() {
        String id = create("mq-cfg-region");

        signedFor("mq", "eu-west-1")
        .when().get("/v1/configurations").then().statusCode(200)
            .body("configurations.id", not(hasItem(id)))
            .body("maxResults", equalTo(100));
        signedFor("mq", "eu-west-1")
        .when().get("/v1/configurations/{id}", id).then().statusCode(404);
        // The same name is free in another region.
        signedFor("mq", "eu-west-1")
            .body("{\"name\": \"mq-cfg-region\", \"engineType\": \"ACTIVEMQ\", \"engineVersion\": \"5.17.6\"}")
        .when().post("/v1/configurations").then().statusCode(200)
            .body("arn", containsString(":eu-west-1:"))
            .body("authenticationStrategy", equalTo("SIMPLE"));
        signedFor("mq")
        .when().get("/v1/configurations").then().statusCode(200)
            .body("configurations.id", hasItem(id));
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private String create(String name) {
        return signedFor("mq")
            .body("""
                {"name": "%s", "engineType": "ACTIVEMQ", "engineVersion": "5.17.6",
                 "authenticationStrategy": "SIMPLE", "tags": {"env": "test"}}
                """.formatted(name))
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(200)
            .body("arn", startsWith("arn:aws:mq:"))
            .body("name", equalTo(name))
            .body("authenticationStrategy", equalTo("SIMPLE"))
            .body("latestRevision.revision", equalTo(1))
            .extract().path("id");
    }

    @Test
    void configurationRoundTrip() {
        String id = create("mq-cfg-roundtrip");
        String xml = "<broker xmlns=\"http://activemq.apache.org/schema/core\"/>";

        signedFor("mq").body("{\"data\": \"" + b64(xml) + "\", \"description\": \"second\"}")
        .when()
            .put("/v1/configurations/{id}", id)
        .then()
            .statusCode(200)
            .body("id", equalTo(id))
            .body("latestRevision.revision", equalTo(2))
            .body("latestRevision.description", equalTo("second"));

        signedFor("mq")
        .when()
            .get("/v1/configurations/{id}", id)
        .then()
            .statusCode(200)
            .body("name", equalTo("mq-cfg-roundtrip"))
            .body("engineType", equalTo("ACTIVEMQ"))
            .body("engineVersion", equalTo("5.17.6"))
            .body("description", equalTo("second"))
            .body("latestRevision.revision", equalTo(2))
            .body("tags.env", equalTo("test"));

        signedFor("mq")
        .when()
            .get("/v1/configurations/{id}/revisions/2", id)
        .then()
            .statusCode(200)
            .body("configurationId", equalTo(id))
            .body("data", equalTo(b64(xml)));

        signedFor("mq")
        .when()
            .get("/v1/configurations/{id}/revisions", id)
        .then()
            .statusCode(200)
            .body("revisions.revision", hasItem(1))
            .body("revisions.revision", hasItem(2));

        signedFor("mq")
        .when()
            .get("/v1/configurations")
        .then()
            .statusCode(200)
            .body("configurations.id", hasItem(id));

        signedFor("mq")
        .when()
            .delete("/v1/configurations/{id}", id)
        .then()
            .statusCode(200)
            .body("configurationId", equalTo(id));

        signedFor("mq")
        .when()
            .get("/v1/configurations/{id}", id)
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void tagsOnConfigurationArn() {
        String id = create("mq-cfg-tags");
        String arn = signedFor("mq").when().get("/v1/configurations/{id}", id).path("arn");

        signedFor("mq").body("{\"tags\": {\"team\": \"a\"}}")
        .when().post("/v1/tags/{arn}", arn).then().statusCode(204);
        signedFor("mq")
        .when().get("/v1/tags/{arn}", arn).then().statusCode(200)
            .body("tags.team", equalTo("a"))
            .body("tags.env", equalTo("test"));
        signedFor("mq")
        .when().queryParam("tagKeys", "env").delete("/v1/tags/{arn}", arn).then().statusCode(204);
        signedFor("mq")
        .when().get("/v1/tags/{arn}", arn).then().statusCode(200)
            .body("tags.team", equalTo("a"))
            .body("tags.env", nullValue());
    }

    @Test
    void duplicateNameConflicts() {
        create("mq-cfg-dup");
        signedFor("mq").body("{\"name\": \"mq-cfg-dup\", \"engineType\": \"ACTIVEMQ\", \"engineVersion\": \"5.17.6\"}")
        .when().post("/v1/configurations").then().statusCode(409);
    }

    @Test
    void kafkaSignedRequestsStillReachMsk() {
        String properties = b64("auto.create.topics.enable=true");

        String arn = signedFor("kafka")
            .body("""
                {"name": "mq-split-msk", "kafkaVersions": ["3.6.0"], "serverProperties": "%s"}
                """.formatted(properties))
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(200)
            .body("arn", containsString(":kafka:"))
            .extract().path("arn");

        signedFor("kafka")
        .when()
            .get("/v1/configurations")
        .then()
            .statusCode(200)
            .body("configurations.name", hasItem("mq-split-msk"));

        // The MQ listing must not contain the MSK configuration, nor the MSK listing the MQ ones.
        signedFor("mq")
        .when()
            .get("/v1/configurations")
        .then()
            .statusCode(200)
            .body("configurations.name", not(hasItem("mq-split-msk")));

        signedFor("kafka").when().delete("/v1/configurations/{arn}", arn).then().statusCode(200);
    }

    @Test
    void internalPrefixIsNotReachableUnlessSignedForMq() {
        given().when().get("/_mq/v1/configurations").then().statusCode(400);
    }
}
