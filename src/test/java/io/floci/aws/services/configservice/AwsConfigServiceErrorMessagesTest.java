package io.floci.aws.services.configservice;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.configservice.model.ConfigEvaluation;
import io.floci.aws.services.configservice.model.ConfigRule;
import io.floci.aws.services.configservice.model.ConfigRuleSource;
import io.floci.aws.services.configservice.model.ConfigurationRecorder;
import io.floci.aws.services.configservice.model.DeliveryChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Not-found and precondition wording that AWS Config returns, read back from the thrown error. */
class AwsConfigServiceErrorMessagesTest {

    private static final String REGION = "us-east-1";

    private final AwsConfigService service =
            new AwsConfigService(new RegionResolver(REGION, "000000000000"), null);

    private static void assertError(String code, String message, Executable call) {
        AwsException ex = assertThrows(AwsException.class, call);
        assertEquals(code, ex.getErrorCode());
        assertEquals(message, ex.getMessage());
    }

    private void putRule(String name, String identifier) {
        service.putConfigRule(REGION, new ConfigRule(name, null, null, null, null,
                new ConfigRuleSource("AWS", identifier, null, null), null, null, null, null, null));
    }

    @Test
    void ruleNotFoundNamesTheRuleExceptOnDescribe() {
        assertError("NoSuchConfigRuleException",
                "The ConfigRule 'ghost' provided in the request is invalid. Please check the configRule name.",
                () -> service.deleteConfigRule(REGION, "ghost"));
        assertError("NoSuchConfigRuleException",
                "The ConfigRule 'ghost' provided in the request is invalid. Please check the configRule name.",
                () -> service.deleteEvaluationResults(REGION, "ghost"));
        assertError("NoSuchConfigRuleException",
                "One or more ConfigRules provided in the request are invalid. Please check the configRule names.",
                () -> service.describeConfigRules(REGION, List.of("ghost")));
    }

    @Test
    void putEvaluationsWithATokenNamingNoRuleIsAnInvalidToken() {
        ConfigEvaluation evaluation = new ConfigEvaluation("AWS::S3::Bucket", "b", "COMPLIANT", null,
                1700000000.0, null, null);
        assertError("InvalidResultTokenException", "Result Token provided is invalid",
                () -> service.putEvaluations(REGION, "bogus-token", List.of(evaluation), false));
    }

    @Test
    void putExternalEvaluationRequiresAProcessCheckRule() {
        putRule("managed", "S3_BUCKET_VERSIONING_ENABLED");
        ConfigEvaluation evaluation = new ConfigEvaluation("AWS::S3::Bucket", "b", "COMPLIANT", null,
                1700000000.0, null, null);
        assertError("InvalidParameterValueException",
                "This API can only be applied to rules with sourceIdentifier as AWS_CONFIG_PROCESS_CHECK.",
                () -> service.putExternalEvaluation(REGION, "managed", evaluation));
        putRule("process-check", "AWS_CONFIG_PROCESS_CHECK");
        service.putExternalEvaluation(REGION, "process-check", evaluation);
    }

    @Test
    void deliveryChannelAndRetentionMessagesNameTheRequestedName() {
        assertError("NoAvailableConfigurationRecorderException",
                "Configuration recorder is not available to put delivery channel.",
                () -> service.putDeliveryChannel(REGION, new DeliveryChannel("dc", "bucket", null, null, null, null)));
        assertError("NoSuchDeliveryChannelException", "Cannot find delivery channel with specified name 'dc'.",
                () -> service.deleteDeliveryChannel(REGION, "dc"));
        assertError("NoSuchRetentionConfigurationException",
                "Cannot find retention configuration with specified name 'r'.",
                () -> service.deleteRetentionConfiguration(REGION, "r"));
    }

    @Test
    void recorderByNameAndRunningChannelDeleteWording() {
        String notFound = "The name of the configuration recorder you entered cannot be found. "
                + "Verify the configuration recorder name, and try again.";
        assertError("NoSuchConfigurationRecorderException", notFound,
                () -> service.startConfigurationRecorder(REGION, "ghost"));
        assertError("NoSuchConfigurationRecorderException", notFound,
                () -> service.describeConfigurationRecorders(REGION, List.of("ghost"), null));

        service.putConfigurationRecorder(REGION, new ConfigurationRecorder(null, "rec", "arn:aws:iam::000000000000:role/r",
                null, null), null);
        service.putDeliveryChannel(REGION, new DeliveryChannel("dc", "bucket", null, null, null, null));
        service.startConfigurationRecorder(REGION, "rec");
        assertError("LastDeliveryChannelDeleteFailedException",
                "Failed to delete last specified delivery channel with name 'dc', "
                        + "because there is a running configuration recorder.",
                () -> service.deleteDeliveryChannel(REGION, "dc"));
    }
}
