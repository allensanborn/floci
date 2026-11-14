package io.floci.aws.services.iam;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.floci.aws.testing.IamTestPolicies.IDENTITY_POLICY;
import static io.floci.aws.testing.IamTestPolicies.TRUST_POLICY;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * The shape of {@code Statement} in a policy document a write is handed: one statement object or a
 * list of them, and a managed policy's list names at least one.
 */
@QuarkusTest
class IamPolicyStatementShapeIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261112/us-east-1/iam/aws4_request";
    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";
    private static final String SYNTAX = "Syntax errors in policy.";

    private static final String STATEMENT_IS_A_STRING = "{\"Version\":\"2012-10-17\",\"Statement\":\"nope\"}";
    private static final String STATEMENT_LIST_IS_EMPTY = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
    private static final String INVALID_EFFECT = "{\"Version\":\"2012-10-17\",\"Statement\":"
            + "[{\"Effect\":\"Maybe\",\"Action\":\"iam:GetUser\",\"Resource\":\"*\"}]}";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private static void expectMalformed(RequestSpecification request, String sentence) {
        request.when().post("/").then().statusCode(400)
                .body(CODE, equalTo("MalformedPolicyDocument"))
                .body(MESSAGE, equalTo(sentence));
    }

    private static void expectOk(RequestSpecification request) {
        request.when().post("/").then().statusCode(200);
    }

    private String create(String action, String nameParam, String prefix, String... extra) {
        String name = unique(prefix);
        RequestSpecification request = iam(action).formParam(nameParam, name);
        for (int i = 0; i < extra.length; i += 2) {
            request.formParam(extra[i], extra[i + 1]);
        }
        expectOk(request);
        String deleteAction = "Delete" + action.substring("Create".length());
        cleanup.register(() -> iam(deleteAction).formParam(nameParam, name).when().post("/").then().statusCode(200));
        return name;
    }

    /** A string where Statement belongs is refused on all three inline holders, and nothing is stored. */
    @Test
    void inlinePolicyWhoseStatementIsAStringIsRefusedOnEveryHolder() {
        String user = create("CreateUser", "UserName", "shape-user");
        String group = create("CreateGroup", "GroupName", "shape-group");
        String role = create("CreateRole", "RoleName", "shape-role", "AssumeRolePolicyDocument", TRUST_POLICY);
        String[][] holders = {
            {"PutUserPolicy", "UserName", user, "ListUserPolicies"},
            {"PutGroupPolicy", "GroupName", group, "ListGroupPolicies"},
            {"PutRolePolicy", "RoleName", role, "ListRolePolicies"},
        };
        for (String[] h : holders) {
            expectMalformed(iam(h[0]).formParam(h[1], h[2]).formParam("PolicyName", "bad")
                    .formParam("PolicyDocument", STATEMENT_IS_A_STRING), SYNTAX);
            expectMalformed(iam(h[0]).formParam(h[1], h[2]).formParam("PolicyName", "bad")
                    .formParam("PolicyDocument", INVALID_EFFECT), "The policy failed legacy parsing");
            iam(h[3]).formParam(h[1], h[2]).when().post("/").then().statusCode(200)
                    .body(not(containsString("<member>bad</member>")));
            expectOk(iam(h[0]).formParam(h[1], h[2]).formParam("PolicyName", "good")
                    .formParam("PolicyDocument", IDENTITY_POLICY));
            cleanup.register(() -> iam(h[0].replace("Put", "Delete")).formParam(h[1], h[2])
                    .formParam("PolicyName", "good").when().post("/").then().statusCode(200));
        }
    }

    /** A managed policy needs a statement: an empty list is refused on create and on a new version. */
    @Test
    void managedPolicyWithAnEmptyStatementListIsRefused() {
        expectMalformed(iam("CreatePolicy").formParam("PolicyName", unique("shape-empty"))
                .formParam("PolicyDocument", STATEMENT_LIST_IS_EMPTY), SYNTAX);
        expectMalformed(iam("CreatePolicy").formParam("PolicyName", unique("shape-string"))
                .formParam("PolicyDocument", STATEMENT_IS_A_STRING), SYNTAX);

        String name = unique("shape-version");
        String arn = iam("CreatePolicy").formParam("PolicyName", name).formParam("PolicyDocument", IDENTITY_POLICY)
            .when().post("/").then().statusCode(200).extract().xmlPath()
            .getString("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        cleanup.register(() -> iam("DeletePolicy").formParam("PolicyArn", arn).when().post("/").then().statusCode(200));
        expectMalformed(iam("CreatePolicyVersion").formParam("PolicyArn", arn)
                .formParam("PolicyDocument", STATEMENT_LIST_IS_EMPTY), SYNTAX);
        // the failed create stored nothing
        iam("GetPolicy").formParam("PolicyArn", arn.replace(name, name + "x")).when().post("/").then()
                .statusCode(404);
    }

    /** The other malformed shapes the suite asserts, on CreatePolicy, and a valid document still works. */
    @Test
    void otherMalformedShapesAreRefusedAndAValidDocumentStillWorks() {
        expectMalformed(iam("CreatePolicy").formParam("PolicyName", unique("shape-json"))
                .formParam("PolicyDocument", "{not valid json"), SYNTAX);
        expectMalformed(iam("CreatePolicy").formParam("PolicyName", unique("shape-nostmt"))
                .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\"}"), SYNTAX);
        expectMalformed(iam("CreatePolicy").formParam("PolicyName", unique("shape-effect"))
                .formParam("PolicyDocument", INVALID_EFFECT), "The policy failed legacy parsing");
        expectMalformed(iam("CreatePolicy").formParam("PolicyName", unique("shape-noaction"))
                .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Resource\":\"*\"}]}"), "Policy statement must contain actions.");
        // a lone statement object is a valid Statement
        String lone = "{\"Version\":\"2012-10-17\",\"Statement\":{\"Effect\":\"Allow\","
                + "\"Action\":\"s3:ListBucket\",\"Resource\":\"*\"}}";
        String arn = iam("CreatePolicy").formParam("PolicyName", unique("shape-lone")).formParam("PolicyDocument", lone)
            .when().post("/").then().statusCode(200).extract().xmlPath()
            .getString("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        cleanup.register(() -> iam("DeletePolicy").formParam("PolicyArn", arn).when().post("/").then().statusCode(200));
    }
}
