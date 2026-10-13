# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN ./gradlew --version --no-daemon
COPY . .
RUN ./gradlew :server:fatJar --no-daemon --console=plain

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 rota
WORKDIR /app
COPY --from=build /src/server/build/dist/rota-server.jar /app/rota-server.jar
USER rota
ENV ROTA_HOST=0.0.0.0 ROTA_PORT=8080 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.language=en -Duser.country=US"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD bash -c ": > /dev/tcp/127.0.0.1/8080" || exit 1
ENTRYPOINT ["java", "-jar", "/app/rota-server.jar"]
