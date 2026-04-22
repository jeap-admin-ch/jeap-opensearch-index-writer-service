package ch.admin.bit.jeap.opensearch.indexwriter.adapter.remotedata;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "jeap.opensearch.indexwriter.search-item-provider")
public class RemoteDataProperties {

    private String oauthClient = "search-item-provider";
}
