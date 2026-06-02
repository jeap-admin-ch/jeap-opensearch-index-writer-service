package ch.admin.bit.jeap.opensearch.indexwriter.adapter.remotedata;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ComponentScan
@EnableConfigurationProperties(RemoteDataProperties.class)
public class AdapterRemoteDataConfiguration {

}
