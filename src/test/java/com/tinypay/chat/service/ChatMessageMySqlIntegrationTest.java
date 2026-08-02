package com.tinypay.chat.service;

import com.tinypay.chat.domain.ChatMessage;
import com.tinypay.chat.domain.ChatSession;
import com.tinypay.chat.domain.FileAttachment;
import com.tinypay.chat.domain.MessageType;
import com.tinypay.chat.domain.SenderRole;
import com.tinypay.chat.dto.GetChatMessageResponse;
import com.tinypay.chat.repository.ChatMessageRepository;
import com.tinypay.chat.repository.ChatSessionRepository;
import com.tinypay.chat.repository.FileAttachmentRepository;
import com.tinypay.dify.domain.AiRequest;
import com.tinypay.dify.domain.AiRequestApiItem;
import com.tinypay.dify.domain.AiRequestStatus;
import com.tinypay.dify.repository.AiRequestApiItemRepository;
import com.tinypay.dify.repository.AiRequestRepository;
import com.tinypay.global.config.JpaAuditingConfig;
import com.tinypay.request.dto.AiRequestResponseStatus;
import com.tinypay.user.domain.User;
import com.tinypay.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:tc:mysql:8.0.36:///tinypay_chat",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class ChatMessageMySqlIntegrationTest {

    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository userRepository;
    @Autowired private ChatSessionRepository chatSessionRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private FileAttachmentRepository fileAttachmentRepository;
    @Autowired private AiRequestRepository aiRequestRepository;
    @Autowired private AiRequestApiItemRepository aiRequestApiItemRepository;

    private ChatMessageService service;
    private Long userId;
    private Long sessionId;

    @BeforeEach
    void setUp() {
        service = new ChatMessageService(
                chatMessageRepository,
                chatSessionRepository,
                fileAttachmentRepository,
                aiRequestRepository,
                aiRequestApiItemRepository,
                Mockito.mock(ChatAnalysisService.class),
                Mockito.mock(DifyAsyncService.class),
                userRepository
        );

        User user = userRepository.save(User.builder()
                .providerId("mysql-integration-user")
                .email("mysql-integration@example.com")
                .nickname("integration")
                .build());
        ChatSession session = chatSessionRepository.save(ChatSession.builder()
                .user(user)
                .title("performance")
                .build());
        userId = user.getId();
        sessionId = session.getId();

        ChatMessage userMessage = chatMessageRepository.save(ChatMessage.builder()
                .user(user)
                .session(session)
                .senderRole(SenderRole.USER)
                .messageType(MessageType.TEXT)
                .content("create a report")
                .build());

        AiRequest request = aiRequestRepository.save(AiRequest.builder()
                .user(user)
                .session(session)
                .message(userMessage)
                .prompt("create a report")
                .status(AiRequestStatus.WAITING_APPROVAL)
                .estimatedTotalCost(new BigDecimal("0.500000"))
                .build());
        userMessage.connectRequest(request);

        chatMessageRepository.save(ChatMessage.builder()
                .session(session)
                .request(request)
                .senderRole(SenderRole.ASSISTANT)
                .messageType(MessageType.TEXT)
                .content("payment required")
                .build());
        chatMessageRepository.save(ChatMessage.builder()
                .session(session)
                .request(request)
                .senderRole(SenderRole.ASSISTANT)
                .messageType(MessageType.TEXT)
                .content("payment completed")
                .build());

        AiRequestApiItem apiItem = AiRequestApiItem.builder()
                .request(request)
                .apiName("report-api")
                .description("Creates a report")
                .estimatedCost(new BigDecimal("0.500000"))
                .executionOrder(1)
                .build();
        aiRequestApiItemRepository.save(apiItem);

        FileAttachment file = FileAttachment.builder()
                .session(session)
                .message(userMessage)
                .fileName("input.csv")
                .fileUrl("https://example.com/input.csv")
                .fileType("text/csv")
                .fileSize(128L)
                .fileHash("hash")
                .storageKey("integration/input.csv")
                .build();
        fileAttachmentRepository.save(file);

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void getChatMessages_preservesResponseAndExecutesConstantNumberOfQueries() {
        Statistics statistics = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getStatistics();
        statistics.clear();

        List<GetChatMessageResponse> result = service.getChatMessages(userId, sessionId);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(GetChatMessageResponse::content)
                .containsExactly("create a report", "payment required", "payment completed");
        assertThat(result.get(0).fileName()).isEqualTo("input.csv");
        assertThat(result.get(1).requestStatus()).isEqualTo(AiRequestResponseStatus.WAITING_APPROVAL);
        assertThat(result.get(1).apiItems()).hasSize(1);
        assertThat(result.get(2).apiItems()).isNull();
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(5);
    }

    @Test
    void recentMessageQuery_returnsOnlyLatestTenInDescendingOrder() {
        ChatSession session = chatSessionRepository.findById(sessionId).orElseThrow();
        for (int index = 0; index < 15; index++) {
            chatMessageRepository.save(ChatMessage.builder()
                    .session(session)
                    .senderRole(SenderRole.USER)
                    .messageType(MessageType.TEXT)
                    .content("message-" + index)
                    .build());
        }
        entityManager.flush();
        entityManager.clear();

        List<ChatMessage> recent =
                chatMessageRepository.findTop10BySessionIdOrderByCreatedAtDescIdDesc(sessionId);

        assertThat(recent).hasSize(10);
        assertThat(recent).extracting(ChatMessage::getContent)
                .containsExactly("message-14", "message-13", "message-12", "message-11", "message-10",
                        "message-9", "message-8", "message-7", "message-6", "message-5");
    }
}
