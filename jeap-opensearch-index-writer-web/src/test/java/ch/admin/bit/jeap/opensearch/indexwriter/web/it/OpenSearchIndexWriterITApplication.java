package ch.admin.bit.jeap.opensearch.indexwriter.web.it;

import ch.admin.bit.jeap.messaging.annotations.JeapMessageConsumerContract;
import ch.admin.bit.jeap.messaging.annotations.JeapMessageConsumerContractsByTemplates;
import ch.admin.bit.jeap.messaging.annotations.JeapMessageProducerContract;
import ch.admin.bit.jeap.opensearch.command.delete.DeleteSearchItemCommand;
import ch.admin.bit.jeap.opensearch.command.upsert.UpsertSearchItemCommand;
import org.springframework.context.annotation.Configuration;

@Configuration
@JeapMessageConsumerContractsByTemplates(appName = "jeap-opensearch-index-writer-it")
@JeapMessageProducerContract(value = UpsertSearchItemCommand.TypeRef.class, topic = OpenSearchIndexWriterITApplication.UPSERT_COMMAND_TOPIC,
        appName = "jeap-opensearch-index-writer-it")
@JeapMessageConsumerContract(value = UpsertSearchItemCommand.TypeRef.class, topic = OpenSearchIndexWriterITApplication.UPSERT_COMMAND_TOPIC,
        appName = "jeap-opensearch-index-writer-it")
@JeapMessageProducerContract(value = DeleteSearchItemCommand.TypeRef.class, topic = OpenSearchIndexWriterITApplication.DELETE_COMMAND_TOPIC,
        appName = "jeap-opensearch-index-writer-it")
@JeapMessageConsumerContract(value = DeleteSearchItemCommand.TypeRef.class, topic = OpenSearchIndexWriterITApplication.DELETE_COMMAND_TOPIC,
        appName = "jeap-opensearch-index-writer-it")
class OpenSearchIndexWriterITApplication {

    static final String UPSERT_COMMAND_TOPIC = "it-upsert-search-item-command";
    static final String DELETE_COMMAND_TOPIC = "it-delete-search-item-command";
}
