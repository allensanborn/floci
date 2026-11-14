package io.floci.aws.services.cloudfront;

import io.floci.aws.core.common.AwsException;

import java.util.regex.Pattern;

/**
 * The CloudFront errors whose wording AWS fixes, one factory per error code so every path that
 * raises a code reads the same. Messages are AWS's own text; none echoes the request.
 */
final class CloudFrontErrors {

    private static final Pattern UUID_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    // The ids of the E- and K-prefixed namespaces (distributions, OAIs, public keys).
    private static final Pattern PREFIXED_ID = Pattern.compile("[EK][A-Z0-9]{13}");

    private static final String ID_FORMAT_INVALID = "Id format is invalid";
    private static final String SAME_NAME = " with the same name already exists within the aws account.";

    private CloudFrontErrors() {
    }

    // ── not found ────────────────────────────────────────────────────────────

    /** An id outside the UUID shape is an unreadable identifier, not an absent resource. */
    static AwsException noSuchResponseHeadersPolicy(String id) {
        return new AwsException("NoSuchResponseHeadersPolicy", UUID_ID.matcher(id).matches()
                ? "The specified response headers policy does not exist." : ID_FORMAT_INVALID, 404);
    }

    static AwsException noSuchKeyGroup(String id) {
        return new AwsException("NoSuchResource", UUID_ID.matcher(id).matches()
                ? "The specified key group does not exist." : ID_FORMAT_INVALID, 404);
    }

    /** The id of another resource kind (an E- or K-prefixed id) was never a profile id. */
    static AwsException noSuchFieldLevelEncryptionProfile(String id) {
        return new AwsException("NoSuchFieldLevelEncryptionProfile", PREFIXED_ID.matcher(id).matches()
                ? ID_FORMAT_INVALID : "The specified field level encryption profile does not exist.", 404);
    }

    static AwsException noSuchFieldLevelEncryptionConfig() {
        return new AwsException("NoSuchFieldLevelEncryptionConfig",
                "The specified field level encryption config does not exist.", 404);
    }

    static AwsException noSuchPublicKey() {
        return new AwsException("NoSuchPublicKey",
                "The specified CloudFront public key does not exist.", 404);
    }

    static AwsException noSuchCloudFrontOriginAccessIdentity() {
        return new AwsException("NoSuchCloudFrontOriginAccessIdentity",
                "The specified CloudFront origin access identity does not exist.", 404);
    }

    static AwsException noSuchMonitoringSubscription() {
        return new AwsException("NoSuchMonitoringSubscription",
                "The monitoring subscription does not exist.", 404);
    }

    // ── managed policies ─────────────────────────────────────────────────────

    static AwsException illegalPolicyUpdate() {
        return new AwsException("IllegalUpdate",
                "The request failed because update is not allowed for this policy.", 400);
    }

    static AwsException illegalPolicyDelete() {
        return new AwsException("IllegalDelete",
                "The request failed because delete is not allowed for this policy.", 400);
    }

    // ── already exists ───────────────────────────────────────────────────────

    static AwsException keyGroupAlreadyExists() {
        return new AwsException("KeyGroupAlreadyExists", "Another key group" + SAME_NAME, 409);
    }

    static AwsException responseHeadersPolicyAlreadyExists() {
        return new AwsException("ResponseHeadersPolicyAlreadyExists",
                "Another response headers policy" + SAME_NAME, 409);
    }

    static AwsException publicKeyAlreadyExists() {
        return new AwsException("PublicKeyAlreadyExists",
                "The caller reference that you are using to create a CloudFront public key is associated "
                        + "with another public key in your account.", 409);
    }

    static AwsException cloudFrontOriginAccessIdentityAlreadyExists() {
        return new AwsException("CloudFrontOriginAccessIdentityAlreadyExists",
                "The caller reference you are using to create the CloudFront origin access identity "
                        + "is associated with another identity.", 409);
    }

    /** AWS sends no message for a duplicate function name. */
    static AwsException functionAlreadyExists() {
        return new AwsException("FunctionAlreadyExists", "", 409);
    }

    // ── in use / immutable / too long ────────────────────────────────────────

    static AwsException publicKeyInUse() {
        return new AwsException("PublicKeyInUse",
                "The Cloudfront public key is currently associated with either Key Group or a field level "
                        + "encryption profile. Please disassociate the key before deleting.", 409);
    }

    static AwsException originAccessControlInUse() {
        return new AwsException("OriginAccessControlInUse",
                "The CloudFront origin access control is still being used.", 409);
    }

    static AwsException cloudFrontOriginAccessIdentityInUse() {
        return new AwsException("CloudFrontOriginAccessIdentityInUse",
                "The CloudFront origin access identity is still being used.", 409);
    }

    static AwsException cannotChangeImmutablePublicKeyFields() {
        return new AwsException("CannotChangeImmutablePublicKeyFields",
                "You cannot modify encoded material and name of a public key once created.", 400);
    }

    static AwsException tooLongContentSecurityPolicy() {
        return new AwsException("TooLongCSPInResponseHeadersPolicy",
                "Your request contains longer Content Security Policy value in the Response Headers Policy "
                        + "than is allowed.", 400);
    }

    // ── references ───────────────────────────────────────────────────────────

    /** A key group Items entry naming a public key that does not exist. */
    static AwsException publicKeyMissingFromKeyGroup() {
        return new AwsException("InvalidArgument",
                "The specified CloudFront public key does not exist in key group.", 400);
    }

    static AwsException trustedKeyGroupDoesNotExist() {
        return new AwsException("TrustedKeyGroupDoesNotExist",
                "One or more of your trusted keygroups do not exist.", 400);
    }

    static AwsException trustedKeyGroupsEnabledWithoutItems() {
        return new AwsException("InvalidArgument",
                "The parameter TrustedKeyGroups must contain items if enabled.", 400);
    }

    static AwsException inconsistentTrustedKeyGroupQuantity(int quantity, int actual) {
        return new AwsException("InconsistentQuantities",
                "The specified quantity of TrustedKeyGroups (" + quantity
                        + ") does not match the actual quantity supplied (" + actual + ").", 400);
    }
}
