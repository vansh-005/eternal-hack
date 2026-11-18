FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
# Windows checkouts may carry CRLF line endings, which break the wrapper's shebang
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
ARG MODULE
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -pl ${MODULE} -am -DskipTests package

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
ARG MODULE
COPY --from=build /src/${MODULE}/target/app.jar /app/app.jar
ENV JAVA_TOOL_OPTIONS="-Xmx512m -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java","-jar","/app/app.jar"]
