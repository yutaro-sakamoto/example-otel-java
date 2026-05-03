# syntax=docker/dockerfile:1.7
FROM gradle:8.10-jdk21 AS build
WORKDIR /workspace

COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY services/order-service services/order-service
COPY services/payment-service/build.gradle.kts services/payment-service/build.gradle.kts

RUN gradle :order-service:bootJar --no-daemon

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

ARG OTEL_AGENT_VERSION=2.10.0
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /otel/javaagent.jar

COPY --from=build /workspace/services/order-service/build/libs/app.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-javaagent:/otel/javaagent.jar", "-jar", "/app/app.jar"]
