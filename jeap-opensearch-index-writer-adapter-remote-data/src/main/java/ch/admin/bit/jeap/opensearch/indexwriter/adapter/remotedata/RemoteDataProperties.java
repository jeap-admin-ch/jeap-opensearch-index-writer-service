package ch.admin.bit.jeap.opensearch.indexwriter.adapter.remotedata;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "jeap.opensearch.indexwriter.search-item-provider")
public class RemoteDataProperties {

    private Duration timeout = Duration.ofSeconds(30);
}
