FROM sbtscala/scala-sbt:eclipse-temurin-17.0.4_1.7.1_3.2.0 as builder

WORKDIR /app

COPY . .

RUN sbt stage

FROM openjdk:17-jdk-slim

WORKDIR /app

COPY --from=builder /app/target/universal/stage /app

EXPOSE 8081

CMD ["./bin/rounting-api"]

# docker build -t rounting-api -f rounting-api.dockerfile .
# docker run -d -p 8083:8081 rounting-api