package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class IndexTemplateSettings {
    private Integer numberOfShards;
    private Integer numberOfReplicas;
    private String refreshInterval;
}
