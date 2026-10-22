FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle,sharing=locked ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system mevether && useradd --system --gid mevether mevether
COPY --from=build /workspace/build/libs/mevether-0.0.1-SNAPSHOT.jar /app/mevether.jar
USER mevether
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/mevether.jar"]
