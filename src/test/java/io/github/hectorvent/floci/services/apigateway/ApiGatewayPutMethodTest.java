package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ApiGatewayPutMethodTest {

    private static String[] newResource() {
        String apiId = given().contentType(ContentType.JSON).body("{\"name\":\"pm-api\"}")
                .post("/restapis").then().statusCode(201).extract().path("id");
        String rootId = given().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        String resId = given().contentType(ContentType.JSON).body("{\"pathPart\":\"pets\"}")
                .post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");
        return new String[] {apiId, resId};
    }

    private static String url(String[] r) {
        return "/restapis/" + r[0] + "/resources/" + r[1] + "/methods/GET";
    }

    @Test
    void operationNameRoundTripsAndPatches() {
        String[] r = newResource();
        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\",\"operationName\":\"listPets\"}")
                .put(url(r)).then().statusCode(201).body("operationName", equalTo("listPets"));
        given().get(url(r)).then().statusCode(200).body("operationName", equalTo("listPets"));
        given().get("/restapis/" + r[0] + "/resources/" + r[1] + "?embed=methods")
                .then().statusCode(200).body("resourceMethods.GET.operationName", equalTo("listPets"));

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/operationName\",\"value\":\"getPets\"}]}")
                .patch(url(r)).then().statusCode(200);
        given().get(url(r)).then().statusCode(200).body("operationName", equalTo("getPets"));
    }

    @Test
    void duplicatePutMethodIsConflictAndKeepsOriginal() {
        String[] r = newResource();
        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\",\"operationName\":\"first\"}")
                .put(url(r)).then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"AWS_IAM\",\"operationName\":\"second\"}")
                .put(url(r)).then().statusCode(409)
                .body("__type", containsString("ConflictException"))
                .body("message", equalTo("Method already exists for this resource"));
        given().get(url(r)).then().statusCode(200)
                .body("authorizationType", equalTo("NONE"))
                .body("operationName", equalTo("first"));
    }
}
