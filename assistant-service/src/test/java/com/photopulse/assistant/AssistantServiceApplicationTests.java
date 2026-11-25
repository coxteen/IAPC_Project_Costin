package com.photopulse.assistant;

import com.photopulse.assistant.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.ai.vectorstore.qdrant.initialize-schema=false"
})
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class
})
class AssistantServiceApplicationTests {

    @MockitoBean
    private VectorStore vectorStore;

    @MockitoBean
    private AuditLogRepository auditLogRepository;

    @MockitoBean
    private ChatClient.Builder chatClientBuilder;

    @Test
    void contextLoads() {
    }
}