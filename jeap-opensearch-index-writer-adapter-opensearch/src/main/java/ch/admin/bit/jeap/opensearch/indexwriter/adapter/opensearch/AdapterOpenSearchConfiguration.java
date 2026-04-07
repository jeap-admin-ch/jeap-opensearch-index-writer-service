package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@ComponentScan(basePackages = "ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch")
@EnableConfigurationProperties(AdapterOpenSearchProperties.class)
public class AdapterOpenSearchConfiguration {
}
