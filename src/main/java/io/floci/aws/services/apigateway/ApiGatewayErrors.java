package io.floci.aws.services.apigateway;

import io.floci.aws.core.common.AwsException;

/**
 * The 404 refusals API Gateway words as "Invalid &lt;Resource&gt; identifier specified", so every
 * lookup of the same kind says the same thing about the same failure.
 */
final class ApiGatewayErrors {

    private ApiGatewayErrors() {
    }

    /** "Invalid {@code kind} identifier specified", with the kind spelled as API Gateway spells it. */
    static AwsException notFound(String kind) {
        return new AwsException("NotFoundException", "Invalid " + kind + " identifier specified", 404);
    }

    /** The REST API lookup alone names the id, qualified by the account the lookup ran in. */
    static AwsException restApiNotFound(String accountId, String apiId) {
        return new AwsException("NotFoundException",
                "Invalid API identifier specified " + accountId + ":" + apiId, 404);
    }

    /** A usage plan id the plan store does not hold, and also a key the plan does not hold. */
    static AwsException usagePlanNotFound() {
        return new AwsException("NotFoundException", "Invalid Usage Plan ID specified", 404);
    }

    /** The refusal for a patch whose path the operation cannot set, listing the paths it can. */
    static AwsException invalidPatchPath(String path, String op, String allowed) {
        return new AwsException("BadRequestException",
                "Invalid patch path  '" + path + "' specified for op '" + op + "'. Must be one of: " + allowed, 400);
    }
}
