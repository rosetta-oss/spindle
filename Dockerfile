# Build:  docker build -t spindle .
# Run:    docker run -p 8080:8080 -v $PWD/config:/opt/spindle/config -v $PWD/drivers:/opt/spindle/drivers spindle
FROM clojure:temurin-21-tools-deps AS build
WORKDIR /src
COPY deps.edn build.clj ./
RUN clojure -P && clojure -T:build clean 2>/dev/null || true
COPY src src
COPY resources resources
RUN clojure -T:build uber

FROM eclipse-temurin:21-jre
WORKDIR /opt/spindle
COPY --from=build /src/target/spindle.jar /opt/spindle/spindle.jar
RUN mkdir -p /opt/spindle/drivers /opt/spindle/config /opt/spindle/explain-archive
ENV SPINDLE_CONFIG_DIR=/opt/spindle/config SPINDLE_PORT=8080 SPINDLE_EXPLAIN_DIR=/opt/spindle/explain-archive
EXPOSE 8080
# drivers/*.jar (e.g. jConnect, MySQL Connector/J) are added to the classpath at start
CMD ["sh", "-c", "exec java $JAVA_OPTS -cp /opt/spindle/spindle.jar:/opt/spindle/drivers/* spindle.main"]
