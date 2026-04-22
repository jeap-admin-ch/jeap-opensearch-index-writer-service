package ch.admin.bit.jeap.opensearch.indexwriter.index.config.repository;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@ComponentScan(basePackages = "ch.admin.bit.jeap.opensearch.indexwriter.index.config.repository")
@EnableConfigurationProperties(IndexConfigRepositoryProperties.class)
public class IndexConfigRepositoryConfiguration {
}
