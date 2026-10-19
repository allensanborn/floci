package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.xml.HasXPath.hasXPath;

/**
 * CreateFlowLogs dropped its TagSpecification, so Terraform's aws_flow_log tags never read back and
 * every plan after apply wanted to add them again.
 */
@QuarkusTest
class Ec2FlowLogTagsIntegrationTest {

    @Test
    void aFlowLogKeepsTheTagsItWasCreatedWith() {
        String vpcId = given().formParam("Action", "CreateVpc").formParam("CidrBlock", "10.21.0.0/16")
                .when().post("/").then().statusCode(200).extract().xmlPath().getString("CreateVpcResponse.vpc.vpcId");
        String flowLogId = given()
                .formParam("Action", "CreateFlowLogs")
                .formParam("ResourceType", "VPC")
                .formParam("ResourceId.1", vpcId)
                .formParam("TrafficType", "ALL")
                .formParam("LogDestinationType", "s3")
                .formParam("LogDestination", "arn:aws:s3:::flow-logs-test-bucket")
                .formParam("TagSpecification.1.ResourceType", "vpc-flow-log")
                .formParam("TagSpecification.1.Tag.1.Key", "Name")
                .formParam("TagSpecification.1.Tag.1.Value", "kthw")
                .when().post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateFlowLogsResponse.flowLogIdSet.item");

        given().formParam("Action", "DescribeFlowLogs").formParam("FlowLogId.1", flowLogId)
                .when().post("/").then().statusCode(200)
                .body(hasXPath("//*[local-name()='flowLogSet']/*[local-name()='item']/*[local-name()='tagSet']"
                        + "/*[local-name()='item']/*[local-name()='value']", equalTo("kthw")));
    }
}
