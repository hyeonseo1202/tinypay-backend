package com.tinypay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;

@Tag("integration")
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:tc:mysql:8.0.36:///tinypay_context",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false",
		"jwt.secret=01234567890123456789012345678901",
		"cloud.aws.credentials.access-key=test-access-key",
		"cloud.aws.credentials.secret-key=test-secret-key",
		"cloud.aws.s3.bucket=test-bucket",
		"google.client-id=test-client-id",
		"coolsms.api-key=test-api-key",
		"coolsms.api-secret=test-api-secret",
		"coolsms.from-number=01000000000",
		"dify.base-url=http://localhost",
		"dify.chat-analysis-api-key=test-chat-key",
		"dify.service-execution-api-key=test-execution-key",
		"blockchain.rpc-url=http://localhost:8545",
		"blockchain.mock-usdc-address=0x0000000000000000000000000000000000000001",
		"blockchain.tiny-payment-address=0x0000000000000000000000000000000000000002",
		"blockchain.server-wallet.address=0x0000000000000000000000000000000000000003",
		"blockchain.server-wallet.private-key=1111111111111111111111111111111111111111111111111111111111111111",
		"blockchain.receiver-wallet.address=0x0000000000000000000000000000000000000004",
		"blockchain.encryption.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class TinypayBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
