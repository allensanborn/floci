package io.floci.aws.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

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
                .then().statusCode(200).body("update.type", equalTo("UpgradePolicyUpdate"))
                .body("update.params[0].type", equalTo("UpgradePolicy"))
                .body("update.params[0].value", equalTo("{\"supportType\":\"STANDARD\"}"));
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

        given().when().get("/clusters/" + CLUSTER)
                .then().statusCode(200)
                .body("cluster.computeConfig.enabled", equalTo(true))
                .body("cluster.storageConfig.blockStorage.enabled", equalTo(true))
                .body("cluster.kubernetesNetworkConfig.elasticLoadBalancing.enabled", equalTo(true))
                .body("cluster.kubernetesNetworkConfig.serviceIpv4Cidr", notNullValue());
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

    @Test
    void controlPlaneEgressModeIsAppliedWithItsOwnUpdateType() {
        update("{\"resourcesVpcConfig\":{\"controlPlaneEgressMode\":\"CUSTOMER_ROUTED\"}}")
                .then().statusCode(200)
                .body("update.type", equalTo("ControlPlaneEgressUpdate"))
                .body("update.params[0].type", equalTo("ControlPlaneEgressMode"));
        given().when().get("/clusters/" + CLUSTER)
                .then().body("cluster.resourcesVpcConfig.controlPlaneEgressMode", equalTo("CUSTOMER_ROUTED"));
        update("{\"resourcesVpcConfig\":{\"controlPlaneEgressMode\":\"BOGUS\"}}")
                .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void loggingInTheProvidersShapeEnablesOneTypeAndDisablesTheRest() {
        update("{\"logging\":{\"clusterLogging\":["
                + "{\"types\":[\"api\",\"scheduler\"],\"enabled\":true},"
                + "{\"types\":[\"audit\",\"authenticator\",\"controllerManager\"],\"enabled\":false}]}}")
                .then().statusCode(200);
        given().when().get("/clusters/" + CLUSTER)
                .then().statusCode(200)
                .body("cluster.logging.clusterLogging.find { it.enabled == true }.types",
                        containsInAnyOrder("api", "scheduler"))
                .body("cluster.logging.clusterLogging.find { it.enabled == false }.types",
                        containsInAnyOrder("audit", "authenticator", "controllerManager"));

        // Removing every type: an empty enabled group plus all five disabled.
        update("{\"logging\":{\"clusterLogging\":[{\"types\":[],\"enabled\":true},"
                + "{\"types\":[\"api\",\"audit\",\"authenticator\",\"controllerManager\",\"scheduler\"],"
                + "\"enabled\":false}]}}").then().statusCode(200);
        given().when().get("/clusters/" + CLUSTER)
                .then().body("cluster.logging.clusterLogging.findAll { it.enabled == true }", hasSize(0));
    }

    @Test
    void badPublicAccessCidrIsRejectedAndLeavesTheClusterUnchanged() {
        for (String cidrs : new String[] {"[\"not-a-cidr\"]", "[\"10.0.0.0/33\"]", "[\"10.0.0.1\"]",
                "[null]", "[\"2001:db8::/32\"]"}) {
            update("{\"resourcesVpcConfig\":{\"endpointPrivateAccess\":true,\"publicAccessCidrs\":" + cidrs + "}}")
                    .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
        }
        given().when().get("/clusters/" + CLUSTER)
                .then().statusCode(200)
                .body("cluster.resourcesVpcConfig.endpointPrivateAccess", equalTo(false))
                .body("cluster.resourcesVpcConfig.publicAccessCidrs", contains("0.0.0.0/0"));
    }

    @Test
    void subnetAndSecurityGroupUpdatesAreAppliedAndWrongVpcSubnetIsRejected() {
        String vpc = ec2("CreateVpc", "CreateVpcResponse.vpc.vpcId", "CidrBlock", "10.20.0.0/16");
        String subnetA = ec2("CreateSubnet", "CreateSubnetResponse.subnet.subnetId",
                "VpcId", vpc, "CidrBlock", "10.20.1.0/24");
        String subnetB = ec2("CreateSubnet", "CreateSubnetResponse.subnet.subnetId",
                "VpcId", vpc, "CidrBlock", "10.20.2.0/24");
        String otherVpc = ec2("CreateVpc", "CreateVpcResponse.vpc.vpcId", "CidrBlock", "10.30.0.0/16");
        String foreignSubnet = ec2("CreateSubnet", "CreateSubnetResponse.subnet.subnetId",
                "VpcId", otherVpc, "CidrBlock", "10.30.1.0/24");
        String name = "it-update-config-vpc";
        given().contentType(JSON)
                .body("{\"name\":\"" + name + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                        + "\"resourcesVpcConfig\":{\"subnetIds\":[\"" + subnetA + "\"]}}")
                .when().post("/clusters").then().statusCode(200);
        try {
            String url = "/clusters/" + name + "/update-config";
            given().contentType(JSON).body("{\"resourcesVpcConfig\":{\"subnetIds\":[\"" + subnetA + "\",\""
                            + subnetB + "\"]}}")
                    .when().post(url).then().statusCode(200).body("update.type", equalTo("VpcConfigUpdate"));
            given().contentType(JSON).body("{\"resourcesVpcConfig\":{\"securityGroupIds\":[\"sg-0123456789abcdef0\"]}}")
                    .when().post(url).then().statusCode(200).body("update.type", equalTo("VpcConfigUpdate"));
            given().contentType(JSON).body("{\"resourcesVpcConfig\":{\"subnetIds\":[\"" + foreignSubnet + "\"]}}")
                    .when().post(url).then().statusCode(400).body("__type", equalTo("InvalidParameterException"));

            given().when().get("/clusters/" + name)
                    .then().statusCode(200)
                    .body("cluster.resourcesVpcConfig.subnetIds", containsInAnyOrder(subnetA, subnetB))
                    .body("cluster.resourcesVpcConfig.securityGroupIds", contains("sg-0123456789abcdef0"))
                    .body("cluster.resourcesVpcConfig.vpcId", equalTo(vpc));
        } finally {
            given().when().delete("/clusters/" + name);
        }
    }

    @Test
    void listUpdatesFiltersByAddonName() {
        given().contentType(JSON).body("{\"addonName\":\"vpc-cni\"}")
                .when().post("/clusters/" + CLUSTER + "/addons").then().statusCode(200);
        String addonUpdate = given().contentType(JSON).body("{\"resolveConflicts\":\"OVERWRITE\"}")
                .when().post("/clusters/" + CLUSTER + "/addons/vpc-cni/update")
                .then().statusCode(200).extract().path("update.id");
        String clusterUpdate = updateId(update("{\"zonalShiftConfig\":{\"enabled\":false}}"));

        given().when().get("/clusters/" + CLUSTER + "/updates").then().statusCode(200)
                .body("updateIds", contains(clusterUpdate));
        given().queryParam("addonName", "vpc-cni").when().get("/clusters/" + CLUSTER + "/updates")
                .then().statusCode(200).body("updateIds", contains(addonUpdate));
        given().queryParam("nodegroupName", "ng").when().get("/clusters/" + CLUSTER + "/updates")
                .then().statusCode(200).body("updateIds", hasSize(0));
    }

    private static String ec2(String action, String path, String... params) {
        RequestSpecification request = given().formParam("Action", action);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/").then().statusCode(200).extract().path(path);
    }
}
