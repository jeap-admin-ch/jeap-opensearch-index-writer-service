package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IsmExplainPermissionVerifierActivationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(OpenSearchClient.class, () -> mock(OpenSearchClient.class))
            .withUserConfiguration(IsmExplainPermissionVerifier.class);

    @Test
    void probeIsDisabledByDefault() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(IsmExplainPermissionVerifier.class));
    }

    @Test
    void probeIsEnabledByProperty() {
        contextRunner
                .withPropertyValues("jeap.opensearch.indexwriter.maintenance.policy-check.verify-permission-on-startup=true")
                .run(context -> assertThat(context).hasSingleBean(IsmExplainPermissionVerifier.class));
    }
}
