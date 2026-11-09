package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * DescribeFlowLogs reports the DestinationOptions the flow log was created with.
 *
 * <p>CreateFlowLogs ignored them and the describe never emitted them, so a client comparing its
 * declared options against the live flow log saw them as unset and replaced the flow log on every
 * plan. An s3 flow log created without options reports AWS's defaults.
 */
@QuarkusTest
class Ec2FlowLogDestinationOptionsIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260908/us-east-1/ec2/aws4_request";
    private static final String ITEM = "DescribeFlowLogsResponse.flowLogSet.item.destinationOptions";

    private static ValidatableResponse ec2(String action, String... formParams) {
        RequestSpecification request = given().header("Authorization", AUTH)
                .formParam("Action", action);
        for (int i = 0; i < formParams.length; i += 2) {
            request = request.formParam(formParams[i], formParams[i + 1]);
        }
        return request.when().post("/").then();
    }

    private static String vpc() {
        return ec2("CreateVpc", "CidrBlock", "10.72.0.0/16")
            .statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
    }

    private static String createS3FlowLog(String... extraParams) {
        String[] params = {
            "ResourceId.1", vpc(),
            "ResourceType", "VPC",
            "TrafficType", "ALL",
            "LogDestinationType", "s3",
            "LogDestination", "arn:aws:s3:::flow-logs-bucket"
        };
        String[] all = new String[params.length + extraParams.length];
        System.arraycopy(params, 0, all, 0, params.length);
        System.arraycopy(extraParams, 0, all, params.length, extraParams.length);
        return ec2("CreateFlowLogs", all)
            .statusCode(200).extract().path("CreateFlowLogsResponse.flowLogIdSet.item");
    }

    @Test
    void destinationOptionsSurviveTheRoundTrip() {
        String flowLogId = createS3FlowLog(
                "DestinationOptions.FileFormat", "parquet",
                "DestinationOptions.HiveCompatiblePartitions", "true",
                "DestinationOptions.PerHourPartition", "true");

        ec2("DescribeFlowLogs", "FlowLogId.1", flowLogId)
            .statusCode(200)
            .body(ITEM + ".fileFormat", equalTo("parquet"))
            .body(ITEM + ".hiveCompatiblePartitions", equalTo("true"))
            .body(ITEM + ".perHourPartition", equalTo("true"));
    }

    @Test
    void anS3FlowLogWithoutOptionsReportsTheDefaults() {
        String flowLogId = createS3FlowLog();

        ec2("DescribeFlowLogs", "FlowLogId.1", flowLogId)
            .statusCode(200)
            .body(ITEM + ".fileFormat", equalTo("plain-text"))
            .body(ITEM + ".hiveCompatiblePartitions", equalTo("false"))
            .body(ITEM + ".perHourPartition", equalTo("false"));
    }

    /** The provider reads an absent element as "no block", which its diff suppression accepts. */
    @Test
    void aCloudWatchLogsFlowLogOmitsDestinationOptions() {
        String flowLogId = ec2("CreateFlowLogs",
                "ResourceId.1", vpc(),
                "ResourceType", "VPC",
                "TrafficType", "ALL",
                "LogDestinationType", "cloud-watch-logs",
                "LogGroupName", "flow-logs-group")
            .statusCode(200).extract().path("CreateFlowLogsResponse.flowLogIdSet.item");

        ec2("DescribeFlowLogs", "FlowLogId.1", flowLogId)
            .statusCode(200)
            .body("DescribeFlowLogsResponse.flowLogSet.item.flowLogId", equalTo(flowLogId))
            .body("DescribeFlowLogsResponse.flowLogSet.item.children().collect { it.name() }",
                    not(hasItem("destinationOptions")));
    }
}
