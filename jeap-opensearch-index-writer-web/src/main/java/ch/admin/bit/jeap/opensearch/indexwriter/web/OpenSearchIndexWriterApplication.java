package ch.admin.bit.jeap.opensearch.indexwriter.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;

@SpringBootApplication
@Slf4j
public class OpenSearchIndexWriterApplication {


    static void main(String[] args) {
        Environment env = SpringApplication.run(OpenSearchIndexWriterApplication.class, args).getEnvironment();

        log.info("""
            
            ----------------------------------------------------------
            \t\
            {} is running!\s
            \t\
            Profile(s): \t\t\t{}\
            
            ----------------------------------------------------------""",
                env.getProperty("spring.application.name"),
                env.getActiveProfiles());
    }
}
