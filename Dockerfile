# syntax=docker/dockerfile:1

FROM sbtscala/scala-sbt:eclipse-temurin-11.0.16_1.8.0_2.12.17 AS builder
ADD . /app/
RUN cd /app && sbt compile stage

FROM eclipse-temurin:11 AS app
RUN mkdir -p /shownotegen
COPY --from=builder /app/target/universal/stage /shownotegen/
WORKDIR /shownotegen
ENTRYPOINT bin/devzen-shownote-generator