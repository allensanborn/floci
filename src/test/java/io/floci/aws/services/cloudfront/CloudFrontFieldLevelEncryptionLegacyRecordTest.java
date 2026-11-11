package io.floci.aws.services.cloudfront;

import io.floci.aws.services.cloudfront.model.FieldLevelEncryptionConfig;
import io.floci.aws.services.cloudfront.model.FieldLevelEncryptionProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Records persisted before the whole config was kept have no stored element and must still read. */
class CloudFrontFieldLevelEncryptionLegacyRecordTest {

    @Test
    void configWithoutStoredElementIsRebuiltFromItsFields() {
        FieldLevelEncryptionConfig cfg = new FieldLevelEncryptionConfig();
        cfg.setCallerReference("ref<1>");
        cfg.setComment("old");

        assertEquals("<FieldLevelEncryptionConfig><CallerReference>ref&lt;1&gt;</CallerReference>"
                + "<Comment>old</Comment></FieldLevelEncryptionConfig>",
                CloudFrontController.fleConfigXml(cfg));
    }

    @Test
    void profileWithoutStoredElementIsRebuiltFromItsFields() {
        FieldLevelEncryptionProfile profile = new FieldLevelEncryptionProfile();
        profile.setName("p");
        profile.setCallerReference("ref");

        assertEquals("<FieldLevelEncryptionProfileConfig><Name>p</Name><CallerReference>ref</CallerReference>"
                + "<Comment></Comment><EncryptionEntities><Quantity>0</Quantity></EncryptionEntities>"
                + "</FieldLevelEncryptionProfileConfig>",
                CloudFrontController.fleProfileConfigXml(profile));
    }
}
