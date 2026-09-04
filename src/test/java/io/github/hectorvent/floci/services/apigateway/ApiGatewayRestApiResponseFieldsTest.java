package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * RestApi response members that clients read but that a caller never supplies:
 * rootResourceId, apiStatus, apiKeySource and disableExecuteApiEndpoint.
 *
 * @see <a href="https://docs.aws.amazon.com/apigateway/latest/api/API_GetRestApi.html">GetRestApi</a>
 */
@QuarkusTest
class ApiGatewayRestApiResponseFieldsTest {

    private String createApi(String name) {
        return given()
            .contentType("application/json")
            .body("{\"name\":\"" + name + "\"}")
        .when()
            .post("/restapis")
        .then()
            .statusCode(201)
            .extract().path("id");
    }

    @Test
    void createRestApiReportsTheRootResourceId() {
        given()
            .contentType("application/json")
            .body("{\"name\":\"root-id-on-create\"}")
        .when()
            .post("/restapis")
        .then()
            .statusCode(201)
            .body("rootResourceId", notNullValue());
    }

    @Test
    void getRestApiReportsTheIdOfTheResourceAtRoot() {
        String apiId = createApi("root-id-on-get");

        // Whatever GetResources calls the "/" resource is what the API must report,
        // otherwise a resource parented on rootResourceId would be orphaned.
        String rootFromResources = given()
        .when()
            .get("/restapis/" + apiId + "/resources")
        .then()
            .statusCode(200)
            // A freshly created API has exactly one resource: "/".
            .body("item.size()", equalTo(1))
            .body("item[0].path", equalTo("/"))
            .extract().path("item[0].id");

        given()
        .when()
            .get("/restapis/" + apiId)
        .then()
            .statusCode(200)
            .body("rootResourceId", equalTo(rootFromResources));
    }

    @Test
    void theReportedRootResourceIdCanParentANewResource() {
        String apiId = createApi("root-id-usable");
        String rootId = given()
        .when()
            .get("/restapis/" + apiId)
        .then()
            .statusCode(200)
            .extract().path("rootResourceId");

        given()
            .contentType("application/json")
            .body("{\"pathPart\":\"hello\"}")
        .when()
            .post("/restapis/" + apiId + "/resources/" + rootId)
        .then()
            .statusCode(201)
            .body("path", equalTo("/hello"))
            .body("parentId", equalTo(rootId));
    }

    @Test
    void getRestApiReportsAnAvailableApiStatus() {
        // Clients poll apiStatus for readiness; an absent member is indistinguishable
        // from "not ready", so a waiter never completes.
        String apiId = createApi("api-status");

        given()
        .when()
            .get("/restapis/" + apiId)
        .then()
            .statusCode(200)
            .body("apiStatus", equalTo("AVAILABLE"));
    }

    @Test
    void getRestApiReportsApiKeySourceAndExecuteApiEndpointDefaults() {
        String apiId = createApi("api-defaults");

        given()
        .when()
            .get("/restapis/" + apiId)
        .then()
            .statusCode(200)
            .body("apiKeySource", equalTo("HEADER"))
            .body("disableExecuteApiEndpoint", is(false));
    }

    @Test
    void listRestApisReportsTheSameMembersAsGetRestApi() {
        String apiId = createApi("api-in-list");

        given()
        .when()
            .get("/restapis")
        .then()
            .statusCode(200)
            .body("item.findAll { it.id == '" + apiId + "' }.rootResourceId", everyItem(notNullValue()))
            .body("item.findAll { it.id == '" + apiId + "' }.apiStatus", everyItem(equalTo("AVAILABLE")));
    }
}
