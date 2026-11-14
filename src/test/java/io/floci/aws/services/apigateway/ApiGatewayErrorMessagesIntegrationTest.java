package io.floci.aws.services.apigateway;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.equalTo;

/**
 * The exact code, status and message API Gateway answers each refusal with, one assertion per
 * resource kind: "Invalid &lt;Resource&gt; identifier specified" for a missing record, and the
 * fixed sentence of each validation refusal.
 */
@QuarkusTest
@TestHTTPEndpoint(ApiGatewayController.class)
class ApiGatewayErrorMessagesIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String CERT =
            "arn:aws:acm:us-east-1:000000000000:certificate/11111111-2222-3333-4444-555555555555";

    private static String apiId;
    private static String rootId;
    private static String resourceId;

    @BeforeEach
    void createApi() {
        apiId = given().contentType(JSON).body("{\"name\":\"error-messages-api\"}")
                .post("/restapis").then().statusCode(201).extract().path("id");
        rootId = given().get("/restapis/{a}/resources", apiId).then().statusCode(200).extract().path("item[0].id");
        resourceId = given().contentType(JSON).body("{\"pathPart\":\"work\"}")
                .post("/restapis/{a}/resources/{r}", apiId, rootId).then().statusCode(201).extract().path("id");
        given().contentType(JSON).body("{\"authorizationType\":\"NONE\"}")
                .put("/restapis/{a}/resources/{r}/methods/GET", apiId, resourceId).then().statusCode(201);
    }

    private static void get404(String message, String path, Object... params) {
        given().get(path, params).then().statusCode(404)
                .body("__type", equalTo("NotFoundException")).body("message", equalTo(message));
    }

    private static void refused(String verb, String body, int status, String type, String message, String path,
                                Object... params) {
        given().contentType(JSON).body(body).request(verb, path, params).then().statusCode(status)
                .body("__type", equalTo(type)).body("message", equalTo(message));
    }

    @Test
    void restApiNamesTheAccountQualifiedId() {
        get404("Invalid API identifier specified " + ACCOUNT + ":nosuchapi", "/restapis/nosuchapi");
    }

    @Test
    void missingRecordsNameTheirResourceKind() {
        get404("Invalid Resource identifier specified", "/restapis/{a}/resources/nosuchres", apiId);
        get404("Invalid Method identifier specified",
                "/restapis/{a}/resources/{r}/methods/POST", apiId, resourceId);
        get404("Invalid Response status code specified",
                "/restapis/{a}/resources/{r}/methods/GET/responses/418", apiId, resourceId);
        get404("Invalid Deployment identifier specified", "/restapis/{a}/deployments/nosuchdep", apiId);
        get404("Invalid stage identifier specified", "/restapis/{a}/stages/nosuchstage", apiId);
        get404("Invalid Authorizer identifier specified", "/restapis/{a}/authorizers/nosuchauth", apiId);
        get404("Invalid Request Validator identifier specified",
                "/restapis/{a}/requestvalidators/nosuchval", apiId);
        get404("Invalid Documentation part identifier specified",
                "/restapis/{a}/documentation/parts/nosuchpart", apiId);
        get404("Invalid Vpc Link identifier specified", "/vpclinks/nosuchlink");
        get404("Invalid domain name identifier specified", "/domainnames/nosuch.example.com");
        get404("Invalid model name specified: NoSuchModel", "/restapis/{a}/models/NoSuchModel", apiId);
        given().delete("/restapis/{a}/gatewayresponses/DEFAULT_4XX", apiId).then().statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("Gateway response type not defined on api"));
    }

    @Test
    void integrationOfMethodWithoutOneIsNamedAndDeleteIsANoOp() {
        get404("Invalid Integration identifier specified",
                "/restapis/{a}/resources/{r}/methods/GET/integration", apiId, resourceId);
        // The method does not exist either: still the integration that is named.
        get404("Invalid Integration identifier specified",
                "/restapis/{a}/resources/{r}/methods/POST/integration", apiId, resourceId);
        given().delete("/restapis/{a}/resources/{r}/methods/GET/integration", apiId, resourceId)
                .then().statusCode(204);
    }

    @Test
    void basePathMappingDistinguishesMissingDomainFromMissingMapping() {
        given().contentType(JSON)
                .body("{\"domainName\":\"msg.example.com\",\"regionalCertificateArn\":\"" + CERT
                        + "\",\"endpointConfiguration\":{\"types\":[\"REGIONAL\"]}}")
                .post("/domainnames").then().statusCode(201);
        get404("Invalid base path mapping identifier specified", "/domainnames/msg.example.com/basepathmappings/v1");
        get404("Invalid domain name identifier specified", "/domainnames/other.example.com/basepathmappings/v1");
    }

    @Test
    void usagePlanKeyNamesTheKeyWhenTheKeyIsUnknownAndThePlanWhenItIsNotAssociated() {
        String planId = given().contentType(JSON).body("{\"name\":\"msg-plan\"}")
                .post("/usageplans").then().statusCode(201).extract().path("id");
        String keyId = given().contentType(JSON).body("{\"name\":\"msg-key\"}")
                .post("/apikeys").then().statusCode(201).extract().path("id");
        get404("Invalid API Key identifier specified", "/usageplans/{p}/keys/nosuchkey", planId);
        get404("Invalid API Key identifier specified", "/usageplans/nosuchplan/keys/nosuchkey");
        get404("Invalid Usage Plan ID specified", "/usageplans/{p}/keys/{k}", planId, keyId);
        given().delete("/usageplans/{p}/keys/{k}", planId, keyId).then().statusCode(404)
                .body("message", equalTo("Invalid Usage Plan ID specified"));
    }

    @Test
    void validationRefusalsUseTheFixedSentences() {
        refused("DELETE", "{}", 400, "BadRequestException", "Root resource cannot be deleted",
                "/restapis/{a}/resources/{r}", apiId, rootId);
        refused("POST", "{\"stageName\":\"\",\"deploymentId\":\"d\"}", 400, "BadRequestException",
                "Stage name must be non-empty", "/restapis/{a}/stages", apiId);
        refused("POST", "{\"name\":\"v\",\"targetArns\":[]}", 400, "BadRequestException",
                "Need at least one target arn to create a vpc link", "/vpclinks");
        refused("PATCH", "{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/nope\",\"value\":\"x\"}]}", 400,
                "BadRequestException",
                "Invalid patch path  '/nope' specified for op 'replace'. Must be one of: [/cloudwatchRoleArn]",
                "/account");
        refused("PUT", "{\"statusCode\":\"400\",\"responseParameters\":{\"gatewayresponse.header.X\":\"bad.src\"}}",
                400, "BadRequestException",
                "Invalid mapping expression specified: Validation Result: warnings : [], errors : "
                        + "[Invalid mapping expression specified: bad.src]",
                "/restapis/{a}/gatewayresponses/DEFAULT_4XX", apiId);
    }

    @Test
    void modelNameIsRefusedAsAnUnsupportedPatchPath() {
        given().contentType(JSON).body("{\"name\":\"Pet\",\"contentType\":\"application/json\",\"schema\":\"{}\"}")
                .post("/restapis/{a}/models", apiId).then().statusCode(201);
        refused("PATCH", "{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"x\"}]}", 400,
                "BadRequestException",
                "Invalid patch path  '/name' specified for op 'replace'. Must be one of: [/description, /schema]",
                "/restapis/{a}/models/Pet", apiId);
    }

    @Test
    void domainNameImmutablePathAndImportBodies() {
        given().contentType(JSON)
                .body("{\"domainName\":\"imm.example.com\",\"regionalCertificateArn\":\"" + CERT
                        + "\",\"endpointConfiguration\":{\"types\":[\"REGIONAL\"]}}")
                .post("/domainnames").then().statusCode(201);
        refused("PATCH", "{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/domainName\",\"value\":\"x.com\"}]}",
                400, "BadRequestException", "/domainName Invalid request input", "/domainnames/imm.example.com");
        given().contentType("text/csv").body("not,a,key,import\n").queryParam("format", "csv")
                .post("/apikeys?mode=import").then().statusCode(400)
                .body("message", equalTo("Invalid API Key import content specified for format: csv"));
        given().contentType(JSON).body("{\"swagger\": \"2.0\", \"info\": {\"title\": \"broken\" ")
                .post("/restapis?mode=import").then().statusCode(400)
                .body("message", equalTo("Invalid OpenAPI input."));
        given().contentType(JSON).body("{\"openapi\": \"3.0.1\", \"info\": {\"title\": \"broken\" ")
                .post("/restapis?mode=import").then().statusCode(400)
                .body("message", equalTo("Invalid OAS input"));
    }

    @Test
    void requestModelViolationsAreValidationExceptions() {
        refused("POST", "{\"name\":\"bad-type\",\"endpointConfiguration\":{\"types\":[\"NOPE\"]}}", 400,
                "ValidationException",
                "1 validation error detected: Value '[NOPE]' at 'createRestApiInput.endpointConfiguration.types' "
                        + "failed to satisfy constraint: Member must satisfy constraint: "
                        + "[Member must satisfy enum value set: [PRIVATE, EDGE, REGIONAL]]",
                "/restapis");
        refused("PUT", "{\"statusCode\":\"abc\"}", 400, "ValidationException",
                "1 validation error detected: Value 'abc' at 'putGatewayResponseInput.statusCode' failed to satisfy "
                        + "constraint: Member must satisfy regular expression pattern: [1-5]\\d\\d",
                "/restapis/{a}/gatewayresponses/DEFAULT_4XX", apiId);
        refused("PUT", "{\"openapi\":\"3.0.1\"}", 400, "ValidationException",
                "1 validation error detected: Value 'bogus' at 'mode' failed to satisfy constraint: "
                        + "Member must satisfy enum value set: [overwrite, merge]",
                "/restapis/{a}?mode=bogus", apiId);
        refused("POST", "{\"location\":{\"type\":\"NOPE\"},\"properties\":\"{}\"}", 400, "ValidationException",
                "1 validation error detected: Value 'NOPE' at 'createDocumentationPartInput.location.type' failed to "
                        + "satisfy constraint: Member must satisfy enum value set: [RESPONSE_BODY, RESPONSE, METHOD, "
                        + "MODEL, AUTHORIZER, RESPONSE_HEADER, RESOURCE, PATH_PARAMETER, REQUEST_BODY, "
                        + "QUERY_PARAMETER, API, REQUEST_HEADER]",
                "/restapis/{a}/documentation/parts", apiId);
    }

    @Test
    void patchOpOutsideTheEnumNamesTheOperationInput() {
        refused("PATCH", "{\"patchOperations\":[{\"op\":\"increment\",\"path\":\"/x\",\"value\":\"1\"}]}", 400,
                "ValidationException",
                "1 validation error detected: Value 'increment' at 'updateStageInput.patchOperations.1.member.op' "
                        + "failed to satisfy constraint: Member must satisfy enum value set: "
                        + "[add, remove, move, test, replace, copy]",
                "/restapis/{a}/stages/dev", apiId);
        refused("PATCH", "{\"patchOperations\":[{\"op\":\"nope\",\"path\":\"/name\",\"value\":\"1\"}]}", 400,
                "ValidationException",
                "1 validation error detected: Value 'nope' at 'updateVpcLinkInput.patchOperations.1.member.op' "
                        + "failed to satisfy constraint: Member must satisfy enum value set: "
                        + "[add, remove, move, test, replace, copy]",
                "/vpclinks/anyid");
    }
}
