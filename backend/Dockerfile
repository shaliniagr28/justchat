# Multi-stage build: the JDK + Maven + full dependency cache never end up in the final
# image, only the compiled jar does.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
# Cache dependency resolution as its own layer - only re-runs when pom.xml changes, not
# on every source edit.
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-alpine AS runtime
WORKDIR /app
COPY --from=build /build/target/justchat.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
