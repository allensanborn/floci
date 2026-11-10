package io.floci.aws.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class EksUpdateClusterConfigIntegrationTest {

    private static final String JSON = "application/json";
    private static final String CLUSTER = "it-update-config-cluster";

    @BeforeEach
    void createCluster() {
        given().contentType(JSON)
                .body("{\"name\":\"" + CLUSTER + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\"}")
                .when().post("/clusters")
                .then().statusCode(200);
    }

    @AfterEach
    void deleteCluster() {
        given().when().delete("/clusters/" + CLUSTER);
    }

    private Response update(String body) {
        return given().contentType(JSON).body(body).when().post("/clusters/" + CLUSTER + "/update-config");
    }

    private String updateId(Response response) {
        return response.then().statusCode(200).extract().path("update.id");
    }

    @Test
    void endpointAccessUpdateIsSuccessfulAndReadBackFromDescribeCluster() {
        Response response = update("{\"resourcesVpcConfig\":{\"endpointPublicAccess\":false,"
                + "\"endpointPrivateAccess\":true,\"publicAccessCidrs\":[\"10.0.0.0/8\"]}}");
        String id = updateId(response);
        response.then()
                .body("update.type", equalTo("EndpointAccessUpdate"))
                .body("update.status", equalTo("Successful"))
                .body("update.params.type", containsInAnyOrder(
                        "EndpointPrivateAccess", "EndpointPublicAccess", "PublicAccessCidrs"));

        given().when().get("/clusters/" + CLUSTER + "/updates/" + id)
                .then().statusCode(200)
                .body("update.id", equalTo(id))
                .body("update.status", equalTo("Successful"));
        given().when().get("/clusters/" + CLUSTER)
                .then().statusCode(200)
                .body("cluster.status", equalTo("ACTIVE"))
                .body("cluster.resourcesVpcConfig.endpointPublicAccess", equalTo(false))
                .body("cluster.resourcesVpcConfig.endpointPrivateAccess", equalTo(true))
                .body("cluster.resourcesVpcConfig.publicAccessCidrs", contains("10.0.0.0/8"));
        given().when().get("/clusters/" + CLUSTER + "/updates")
                .then().statusCode(200)
                .body("updateIds", hasItem(id));
    }

    @Test
    void loggingUpdateKeepsTypesTheRequestDoesNotName() {
        update("{\"logging\":{\"clusterLogging\":[{\"types\":[\"api\"],\"enabled\":true}]}}")
                .then().statusCode(200).body("update.type", equalTo("LoggingUpdate"));
        update("{\"logging\":{\"clusterLogging\":[{\"types\":[\"audit\"],\"enabled\":true}]}}")
                .then().statusCode(200);

        given().when().get("/clusters/" + CLUSTER)
                .then().statusCode(200)
                .body("cluster.logging.clusterLogging", hasSize(2))
                .body("cluster.logging.clusterLogging.find { it.enabled == true }.types",
                        containsInAnyOrder("api", "audit"));
    }

    @Test
    void loggingUpdateNamingNoTypesKeepsCurrentState() {
        update("{\"logging\":{\"clusterLogging\":[{\"types\":[\"scheduler\"],\"enabled\":true}]}}")
                .then().statusCode(200);
        update("{\"logging\":{\"clusterLogging\":[{\"types\":[],\"enabled\":false}]}}")
                .then().statusCode(200);

        given().when().get("/clusters/" + CLUSTER)
                .then().statusCode(200)
                .body("cluster.logging.clusterLogging.find { it.enabled == true }.types", hasItem("scheduler"));
    }

    @Test
    void accessConfigUpdateChangesAuthenticationMode() {
        update("{\"accessConfig\":{\"authenticationMode\":\"API_AND_CONFIG_MAP\"}}")
                .then().statusCode(200).body("update.type", equalTo("AccessConfigUpdate"));

        given().when().get("/clusters/" + CLUSTER)
                .then().statusCode(200)
                .body("cluster.accessConfig.authenticationMode", equalTo("API_AND_CONFIG_MAP"));
    }

    @Test
    void storedMembersAreEchoedFromDescribeCluster() {
        update("{\"upgradePolicy\":{\"supportType\":\"STANDARD\"}}")
                .then().statusCode(200).body("update.type", equalTo("UpgradePolicyUpdate"));
        update("{\"zonalShiftConfig\":{\"enabled\":true}}").then().statusCode(200);
        update("{\"remoteNetworkConfig\":{\"remoteNodeNetworks\":[{\"cidrs\":[\"10.1.0.0/16\"]}]}}")
                .then().statusCode(200);

        given().when().get("/clusters/" + CLUSTER)
                .then().statusCode(200)
                .body("cluster.upgradePolicy.supportType", equalTo("STANDARD"))
                .body("cluster.zonalShiftConfig.enabled", equalTo(true))
                .body("cluster.remoteNetworkConfig.remoteNodeNetworks[0].cidrs[0]", equalTo("10.1.0.0/16"));
    }

    @Test
    void autoModeTrioIsOneUpdateType() {
        update("{\"computeConfig\":{\"enabled\":true},\"kubernetesNetworkConfig\":"
                + "{\"elasticLoadBalancing\":{\"enabled\":true}},\"storageConfig\":{\"blockStorage\":{\"enabled\":true}}}")
                .then().statusCode(200).body("update.type", equalTo("AutoModeUpdate"));
    }

    @Test
    void moreThanOneUpdateTypeInARequestIsRejectedAndAppliesNothing() {
        update("{\"logging\":{\"clusterLogging\":[{\"types\":[\"api\"],\"enabled\":true}]},"
                + "\"resourcesVpcConfig\":{\"endpointPrivateAccess\":true}}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo("Only one type of update can be allowed."));

        given().when().get("/clusters/" + CLUSTER)
                .then().statusCode(200)
                .body("cluster.resourcesVpcConfig.endpointPrivateAccess", equalTo(false))
                .body("cluster.logging.clusterLogging.findAll { it.enabled == true }", hasSize(0));
    }

    @Test
    void updateThatLeavesBothEndpointAccessesDisabledIsRejectedOnMergedValues() {
        update("{\"resourcesVpcConfig\":{\"endpointPublicAccess\":false}}")
                .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
        given().when().get("/clusters/" + CLUSTER)
                .then().body("cluster.resourcesVpcConfig.endpointPublicAccess", equalTo(true));

        update("{\"resourcesVpcConfig\":{\"endpointPrivateAccess\":true}}").then().statusCode(200);
        update("{\"resourcesVpcConfig\":{\"endpointPublicAccess\":false}}").then().statusCode(200);
        update("{\"resourcesVpcConfig\":{\"endpointPrivateAccess\":false}}")
                .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void deletionProtectionBlocksDeleteClusterUntilDisabled() {
        String name = "it-update-config-protected";
        given().contentType(JSON)
                .body("{\"name\":\"" + name + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\"}")
                .when().post("/clusters").then().statusCode(200);
        given().contentType(JSON).body("{\"deletionProtection\":true}")
                .when().post("/clusters/" + name + "/update-config")
                .then().statusCode(200).body("update.type", equalTo("DeletionProtectionUpdate"));
        given().when().get("/clusters/" + name)
                .then().body("cluster.deletionProtection", equalTo(true));

        given().when().delete("/clusters/" + name)
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        given().when().get("/clusters/" + name).then().statusCode(200);

        given().contentType(JSON).body("{\"deletionProtection\":false}")
                .when().post("/clusters/" + name + "/update-config").then().statusCode(200);
        given().when().delete("/clusters/" + name).then().statusCode(200);
    }

    @Test
    void requestWithNoUpdateMemberIsRejected() {
        update("{}").then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void invalidAuthenticationModeIsRejected() {
        update("{\"accessConfig\":{\"authenticationMode\":\"BOGUS\"}}")
                .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void unknownClusterIsNotFound() {
        given().contentType(JSON).body("{\"logging\":{}}")
                .when().post("/clusters/no-such-cluster/update-config")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }
}
