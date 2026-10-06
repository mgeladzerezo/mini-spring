# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml ./
COPY mini-spring-core/pom.xml mini-spring-core/
COPY mini-spring-aop/pom.xml mini-spring-aop/
COPY mini-spring-tx/pom.xml mini-spring-tx/
COPY mini-spring-web/pom.xml mini-spring-web/
COPY mini-spring-demo/pom.xml mini-spring-demo/
COPY mini-spring-core/src mini-spring-core/src
COPY mini-spring-aop/src mini-spring-aop/src
COPY mini-spring-tx/src mini-spring-tx/src
COPY mini-spring-web/src mini-spring-web/src
COPY mini-spring-demo/src mini-spring-demo/src
# The tests ran in CI already; the image build only packages.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /src/mini-spring-demo/target/mini-spring-demo.jar /app/
COPY --from=build /src/mini-spring-demo/target/lib /app/lib
COPY docker/Probe.java /app/Probe.java
USER app
EXPOSE 8202
ENTRYPOINT ["java", "-Xmx256m", "-jar", "/app/mini-spring-demo.jar"]
