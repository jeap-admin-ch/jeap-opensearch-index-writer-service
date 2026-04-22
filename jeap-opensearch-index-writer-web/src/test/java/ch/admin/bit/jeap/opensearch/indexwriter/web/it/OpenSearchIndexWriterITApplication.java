package ch.admin.bit.jeap.opensearch.indexwriter.web.it;

import ch.admin.bit.jeap.messaging.annotations.JeapMessageConsumerContractsByTemplates;
import org.springframework.context.annotation.Configuration;

@Configuration
@JeapMessageConsumerContractsByTemplates(appName = "jeap-opensearch-index-writer-it", templatesPath = "opensearch")
class OpenSearchIndexWriterITApplication {
}
