# Payment idempotency

결제 승인 요청은 클라이언트가 생성한 `idempotencyKey`를 요청 본문으로 받는다.
동일 사용자의 키는 최대 100자이며, 결제 요청마다 새 UUID 사용을 권장한다.

```json
{
  "idempotencyKey": "4dddbed4-3537-4ec4-82f8-643b59243e87",
  "estimatedCost": 10.500000,
  "walletPassword": "123456"
}
```

## 처리 규칙

- 최초 요청은 `(user_id, idempotency_key)`에 대한 처리 권한을 DB에 먼저 선점한다.
- 같은 키와 같은 결제가 완료된 뒤 재요청되면 기존 결제 결과를 반환한다.
- 같은 키의 결제가 처리 중이면 `409 Conflict`를 반환한다.
- 같은 키를 다른 요청 ID나 금액에 사용하면 `409 Conflict`를 반환한다.
- 이전 처리가 실패한 키를 다시 사용하면 `409 Conflict`를 반환하며, 재시도에는 새 키가 필요하다.

`payment_idempotency` 테이블의 복합 unique constraint가 여러 서버 인스턴스에서 동시에
같은 요청을 받아도 하나의 요청만 블록체인 결제 단계로 진입하도록 보장한다.

## 장애 안전성

키 선점은 결제 트랜잭션과 분리해 먼저 커밋한다. 외부 결제 호출 이후 애플리케이션이
중단되더라도 해당 키는 `PROCESSING`으로 유지되므로 자동 재전송이 중복 결제를 만들지 않는다.
장시간 `PROCESSING` 상태의 최종 결과 판정은 향후 거래 대사 작업에서 처리한다.
