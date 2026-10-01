package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.account.AccountClient;
import software.amazon.awssdk.services.account.model.AlternateContactType;
import software.amazon.awssdk.services.account.model.ResourceNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("AWS Account Management alternate contacts")
class AccountAlternateContactTest {

    @Test
    void putAndGetAlternateContactUseAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Writes emulator account contact state");

        try (AccountClient account = TestFixtures.accountClient()) {
            account.putAlternateContact(request -> request
                    .alternateContactType(AlternateContactType.SECURITY)
                    .emailAddress("security@example.com")
                    .name("Security Team")
                    .phoneNumber("+1 555 0100")
                    .title("Security"));

            var response = account.getAlternateContact(request -> request
                    .alternateContactType(AlternateContactType.SECURITY));
            assertThat(response.alternateContact()).isNotNull();
            assertThat(response.alternateContact().emailAddress()).isEqualTo("security@example.com");
            assertThat(response.alternateContact().alternateContactType()).isEqualTo(AlternateContactType.SECURITY);
        }
    }

    @Test
    void deleteAlternateContactUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Writes emulator account contact state");

        try (AccountClient account = TestFixtures.accountClient()) {
            account.putAlternateContact(request -> request
                    .alternateContactType(AlternateContactType.BILLING)
                    .emailAddress("billing@example.com")
                    .name("Billing Team")
                    .phoneNumber("+1 555 0101")
                    .title("Billing"));

            account.deleteAlternateContact(request -> request
                    .alternateContactType(AlternateContactType.BILLING));

            assertThatThrownBy(() -> account.getAlternateContact(request -> request
                    .alternateContactType(AlternateContactType.BILLING)))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> account.deleteAlternateContact(request -> request
                    .alternateContactType(AlternateContactType.BILLING)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
