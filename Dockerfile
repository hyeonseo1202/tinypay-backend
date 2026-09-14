# ===== 1단계: 빌드 =====
FROM gradle:8.5-jdk17 AS build
WORKDIR /app

# 의존성 캐시 활용 (build.gradle 먼저 복사)
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY gradlew ./
RUN chmod +x gradlew

# 소스 복사 후 빌드
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# ===== 2단계: 실행 =====
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-Dspring.profiles.active=prod", "-jar", "app.jar"]
