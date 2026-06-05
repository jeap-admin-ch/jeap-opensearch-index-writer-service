package ch.admin.bit.jeap.opensearch.searchitem.api;

import ch.admin.bit.jeap.security.test.resource.configuration.ServletJeapAuthorizationConfig;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

@Configuration
@ComponentScan
@EnableWebSecurity
class OpenSearchSearchItemApiTestConfig extends ServletJeapAuthorizationConfig {

    // You have to provide the system name and the application context to the test support base class.
    OpenSearchSearchItemApiTestConfig(ApplicationContext applicationContext) {
        super("jme", applicationContext);
    }

}
