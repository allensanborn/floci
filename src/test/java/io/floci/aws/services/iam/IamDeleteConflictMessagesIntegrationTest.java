package io.floci.aws.services.iam;

import io.floci.aws.core.common.Totp;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** Pins the raw wire code, status and message of every IAM DeleteConflict, and that removing the dependent clears it. */
@QuarkusTest
class IamDeleteConflictMessagesIntegrationTest {

    private static final String CREDENTIAL =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";
    private static final String POLICY_DOC =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"s3:ListAllMyBuckets\","
                    + "\"Resource\":\"*\"}]}";
    private static final String TRUST_DOC =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    private static final String DETACH = "Cannot delete entity, must detach all policies first.";
    private static final String DELETE_POLICIES = "Cannot delete entity, must delete policies first.";
    private static final String USERS_FROM_GROUP = "Cannot delete entity, must remove users from group first.";
    private static final String ROLES_FROM_PROFILE =
            "Cannot delete entity, must remove roles from instance profile first.";

    private static ValidatableResponse call(String action, String... params) {
        RequestSpecification request = given().formParam("Action", action).header("Authorization", CREDENTIAL);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/").then();
    }

    private static void conflict(String message, String action, String... params) {
        call(action, params).statusCode(409)
                .body("ErrorResponse.Error.Code", equalTo("DeleteConflict"))
                .body("ErrorResponse.Error.Message", equalTo(message));
    }

    private static String name(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String createPolicy() {
        return call("CreatePolicy", "PolicyName", name("dc-pol-"), "PolicyDocument", POLICY_DOC)
                .statusCode(200).extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
    }

    @Test
    void groupWithAttachedPolicyInlinePolicyAndMember() {
        String group = name("dc-grp-");
        String user = name("dc-usr-");
        String arn = createPolicy();
        call("CreateGroup", "GroupName", group).statusCode(200);
        call("CreateUser", "UserName", user).statusCode(200);
        call("AttachGroupPolicy", "GroupName", group, "PolicyArn", arn).statusCode(200);
        call("PutGroupPolicy", "GroupName", group, "PolicyName", "inl", "PolicyDocument", POLICY_DOC).statusCode(200);
        call("AddUserToGroup", "GroupName", group, "UserName", user).statusCode(200);

        conflict(DETACH, "DeleteGroup", "GroupName", group);
        call("DetachGroupPolicy", "GroupName", group, "PolicyArn", arn).statusCode(200);
        conflict(DELETE_POLICIES, "DeleteGroup", "GroupName", group);
        call("DeleteGroupPolicy", "GroupName", group, "PolicyName", "inl").statusCode(200);
        conflict(USERS_FROM_GROUP, "DeleteGroup", "GroupName", group);
        call("RemoveUserFromGroup", "GroupName", group, "UserName", user).statusCode(200);
        call("DeleteGroup", "GroupName", group).statusCode(200);
        call("GetGroup", "GroupName", group).statusCode(404);
    }

    @Test
    void roleWithAttachedAndInlinePolicy() {
        String role = name("dc-role-");
        String arn = createPolicy();
        call("CreateRole", "RoleName", role, "AssumeRolePolicyDocument", TRUST_DOC).statusCode(200);
        call("AttachRolePolicy", "RoleName", role, "PolicyArn", arn).statusCode(200);
        call("PutRolePolicy", "RoleName", role, "PolicyName", "inl", "PolicyDocument", POLICY_DOC).statusCode(200);

        conflict(DETACH, "DeleteRole", "RoleName", role);
        call("DetachRolePolicy", "RoleName", role, "PolicyArn", arn).statusCode(200);
        conflict(DELETE_POLICIES, "DeleteRole", "RoleName", role);
        call("DeleteRolePolicy", "RoleName", role, "PolicyName", "inl").statusCode(200);
        call("DeleteRole", "RoleName", role).statusCode(200);
        call("GetRole", "RoleName", role).statusCode(404);
    }

    @Test
    void userAttachedPolicyAndGroupMembership() {
        String user = name("dc-usr-");
        String group = name("dc-grp-");
        String arn = createPolicy();
        call("CreateUser", "UserName", user).statusCode(200);
        call("CreateGroup", "GroupName", group).statusCode(200);
        call("AttachUserPolicy", "UserName", user, "PolicyArn", arn).statusCode(200);
        call("AddUserToGroup", "GroupName", group, "UserName", user).statusCode(200);

        conflict(DETACH, "DeleteUser", "UserName", user);
        call("DetachUserPolicy", "UserName", user, "PolicyArn", arn).statusCode(200);
        conflict(USERS_FROM_GROUP, "DeleteUser", "UserName", user);
        call("RemoveUserFromGroup", "GroupName", group, "UserName", user).statusCode(200);
        call("DeleteUser", "UserName", user).statusCode(200);
        call("GetUser", "UserName", user).statusCode(404);
    }

    @Test
    void policyWhileAttached() {
        String user = name("dc-usr-");
        String arn = createPolicy();
        call("CreateUser", "UserName", user).statusCode(200);
        call("AttachUserPolicy", "UserName", user, "PolicyArn", arn).statusCode(200);

        conflict("Cannot delete a policy attached to entities.", "DeletePolicy", "PolicyArn", arn);
        call("DetachUserPolicy", "UserName", user, "PolicyArn", arn).statusCode(200);
        call("DeletePolicy", "PolicyArn", arn).statusCode(200);
        call("DeleteUser", "UserName", user).statusCode(200);
    }

    @Test
    void policyDefaultVersion() {
        String arn = createPolicy();
        conflict("Cannot delete the default version of a policy.",
                "DeletePolicyVersion", "PolicyArn", arn, "VersionId", "v1");
        call("DeletePolicy", "PolicyArn", arn).statusCode(200);
    }

    @Test
    void instanceProfileWithRole() {
        String profile = name("dc-ip-");
        String role = name("dc-role-");
        call("CreateRole", "RoleName", role, "AssumeRolePolicyDocument", TRUST_DOC).statusCode(200);
        call("CreateInstanceProfile", "InstanceProfileName", profile).statusCode(200);
        call("AddRoleToInstanceProfile", "InstanceProfileName", profile, "RoleName", role).statusCode(200);

        conflict(ROLES_FROM_PROFILE, "DeleteInstanceProfile", "InstanceProfileName", profile);
        call("RemoveRoleFromInstanceProfile", "InstanceProfileName", profile, "RoleName", role).statusCode(200);
        call("DeleteInstanceProfile", "InstanceProfileName", profile).statusCode(200);
        call("GetInstanceProfile", "InstanceProfileName", profile).statusCode(404);
        call("DeleteRole", "RoleName", role).statusCode(200);
    }

    @Test
    void virtualMfaDeviceWhileEnabled() {
        String user = name("dc-usr-");
        call("CreateUser", "UserName", user).statusCode(200);
        ExtractableResponse<Response> created = call("CreateVirtualMFADevice", "VirtualMFADeviceName", name("dc-mfa-"))
                .statusCode(200).extract();
        String base = "CreateVirtualMFADeviceResponse.CreateVirtualMFADeviceResult.VirtualMFADevice.";
        String serial = created.path(base + "SerialNumber");
        String seed = new String(Base64.getDecoder().decode(((String) created.path(base + "Base32StringSeed")).trim()),
                StandardCharsets.UTF_8);
        long step = Totp.stepAt(Instant.now());
        call("EnableMFADevice", "UserName", user, "SerialNumber", serial,
                "AuthenticationCode1", Totp.codeAt(seed, step - 1),
                "AuthenticationCode2", Totp.codeAt(seed, step)).statusCode(200);

        conflict("MFA VirtualDevice in use. Must deactivate first.", "DeleteVirtualMFADevice", "SerialNumber", serial);
        call("DeactivateMFADevice", "UserName", user, "SerialNumber", serial).statusCode(200);
        call("DeleteVirtualMFADevice", "SerialNumber", serial).statusCode(200);
        call("DeleteUser", "UserName", user).statusCode(200);
    }
}
