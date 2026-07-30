# Payment state machine

결제의 처리 단계를 하나의 `SUCCESS` 값으로 저장하지 않고 다음 상태 전이로 관리한다.

```text
REQUESTED → APPROVED → PAID → VERIFIED → COMPLETED
      └────────────── 각 단계에서 FAILED
```

## 상태 의미

- `REQUESTED`: 결제 기록이 생성됨
- `APPROVED`: 사용자, 예산, 비밀번호 및 잔액 검증을 통과함
- `PAID`: 블록체인 전송이 완료되어 트랜잭션 해시를 확보함
- `VERIFIED`: 블록체인 영수증의 컨트랙트, 수신자 및 금액 검증을 통과함
- `COMPLETED`: 잔액 차감과 결제 기록 처리가 완료됨
- `FAILED`: 처리 중 오류가 발생함

`PaymentLog`만 상태를 변경할 수 있으며 현재 상태와 다음 상태가 정해진 순서와 다르면
`IllegalStateException`을 발생시킨다. 완료 상태와 실패 상태는 다시 변경할 수 없는 종결
상태로 취급한다.

## 실패 추적

실패 시 `failedFromStatus`, `failureReason`, `failedAt`을 함께 기록한다.

예를 들어 블록체인 전송 전에 실패하면 `APPROVED → FAILED`, 전송 후 영수증 검증에서
실패하면 `PAID → FAILED`로 기록된다. 이를 통해 같은 `FAILED` 결과라도 어느 단계에서
문제가 발생했는지 구분할 수 있다.

각 정상 단계의 `approvedAt`, `paidAt`, `verifiedAt`, `completedAt`도 저장해 단계별 처리
시각을 확인할 수 있다.

## 기존 데이터 호환

상태 머신 도입 전의 완료 결제는 `SUCCESS`로 저장되어 있다. 기존 데이터를 읽지 못하는
문제를 방지하기 위해 `SUCCESS`는 레거시 읽기 전용 상태로 유지한다. 신규 결제는
`COMPLETED`만 사용하며 결제 내역과 월 사용액 집계에서는 두 상태를 모두 성공으로 취급한다.

## 트랜잭션 정책

블록체인 실패 시에도 `PaymentLog`의 실패 단계와 원인을 저장해야 하므로 결제 승인
트랜잭션은 처리된 `CustomException`에 대해 롤백하지 않는다. 검증 단계 이전에 발생하는
예외는 변경된 결제 데이터가 없으며, 블록체인 처리 중 발생한 예외는 `FAILED` 상태를
커밋한다.

현재 단계 전이는 하나의 결제 트랜잭션 안에서 실행된다. 프로세스가 비정상 종료되는
상황까지 단계별로 복구하려면 후속 reconciliation 작업에서 멱등성 `PROCESSING` 기록과
블록체인 거래 내역을 비교해 최종 상태를 판정해야 한다.
