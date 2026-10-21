# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /source
COPY pom.xml .
# Cache mount: thư mục ~/.m2 được giữ giữa các lần build, không nằm trong image
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
&& java -Djarmode=tools -jar target/app.jar extract --layers --destination /extracted
FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
&& rm -rf /var/lib/apt/lists/* \
&& useradd --system --uid 10001 app
WORKDIR /app
# Thứ tự từ ít thay đổi -> hay thay đổi
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]