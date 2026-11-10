package io.floci.aws.services.apigatewayv2;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * Integration members that GetIntegration must echo: Description, PassthroughBehavior,
 * ContentHandlingStrategy and IntegrationSubtype are stored on create and update, and an
 * omitted ConnectionType reads back as AWS's default INTERNET.
 */
@QuarkusTest
class ApiGatewayV2IntegrationDescriptionTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void membersRoundTripThroughCreateGetListAndUpdate() {
        String apiId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"int-desc\",\"protocolType\":\"HTTP\"}")
                .when().post("/v2/apis")
                .then().statusCode(201).extract().path("apiId");

        String integrationId = given().contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"HTTP_PROXY","integrationUri":"https://example.com",
                         "integrationMethod":"GET","payloadFormatVersion":"1.0","description":"growth",
                         "passthroughBehavior":"WHEN_NO_MATCH","contentHandlingStrategy":"CONVERT_TO_TEXT"}
                        """)
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then().statusCode(201)
                .body("description", equalTo("growth"))
                .body("connectionType", equalTo("INTERNET"))
                .extract().path("integrationId");

        given().when().get("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then().statusCode(200)
                .body("description", equalTo("growth"))
                .body("connectionType", equalTo("INTERNET"))
                .body("passthroughBehavior", equalTo("WHEN_NO_MATCH"))
                .body("contentHandlingStrategy", equalTo("CONVERT_TO_TEXT"));

        given().when().get("/v2/apis/" + apiId + "/integrations")
                .then().statusCode(200)
                .body("items.description", hasItem("growth"));

        given().contentType(ContentType.JSON)
                .body("{\"description\":\"changed\"}")
                .when().patch("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then().statusCode(200)
                .body("description", equalTo("changed"));

        given().when().get("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then().statusCode(200)
                .body("description", equalTo("changed"))
                .body("integrationUri", equalTo("https://example.com"));
    }

    @Test
    void membersReturnedViaJson11() {
        String amzJson = "application/x-amz-json-1.1";
        String auth = "AWS4-HMAC-SHA256 Credential=test/20260519/us-east-1/apigatewayv2/aws4_request";
        String apiId = given().contentType(amzJson)
                .header("X-Amz-Target", "AmazonApiGatewayV2.CreateApi").header("Authorization", auth)
                .body("{\"Name\":\"int-desc-json11\",\"ProtocolType\":\"HTTP\"}")
                .when().post("/")
                .then().statusCode(201).extract().path("ApiId");

        String integrationId = given().contentType(amzJson)
                .header("X-Amz-Target", "AmazonApiGatewayV2.CreateIntegration").header("Authorization", auth)
                .body("{\"ApiId\":\"" + apiId + "\",\"IntegrationType\":\"HTTP_PROXY\","
                        + "\"IntegrationUri\":\"https://example.com\",\"Description\":\"growth\"}")
                .when().post("/")
                .then().statusCode(201).extract().path("IntegrationId");

        given().contentType(amzJson)
                .header("X-Amz-Target", "AmazonApiGatewayV2.GetIntegration").header("Authorization", auth)
                .body("{\"ApiId\":\"" + apiId + "\",\"IntegrationId\":\"" + integrationId + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Description", equalTo("growth"))
                .body("ConnectionType", equalTo("INTERNET"));
    }

    @Test
    void integrationSubtypeRoundTrips() {
        String apiId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"int-subtype\",\"protocolType\":\"HTTP\"}")
                .when().post("/v2/apis")
                .then().statusCode(201).extract().path("apiId");

        String integrationId = given().contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationSubtype":"SQS-SendMessage",
                         "payloadFormatVersion":"1.0"}
                        """)
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then().statusCode(201).extract().path("integrationId");

        given().when().get("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then().statusCode(200)
                .body("integrationSubtype", equalTo("SQS-SendMessage"));
    }
}
