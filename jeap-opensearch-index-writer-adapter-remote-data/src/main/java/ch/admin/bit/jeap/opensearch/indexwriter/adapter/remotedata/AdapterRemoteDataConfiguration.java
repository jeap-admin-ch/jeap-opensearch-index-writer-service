package ch.admin.bit.jeap.opensearch.indexwriter.adapter.remotedata;

import ch.admin.bit.jeap.security.restclient.JeapOAuth2RestClientBuilderFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ComponentScan
@EnableConfigurationProperties(RemoteDataProperties.class)
public class AdapterRemoteDataConfiguration {

    @Bean
    RestClient searchItemRestClient(
            JeapOAuth2RestClientBuilderFactory oAuth2RestClientBuilderFactory,
            RemoteDataProperties properties) {
        return oAuth2RestClientBuilderFactory
                .createForClientRegistryId(properties.getOauthClient())
                .build();
    }
}
