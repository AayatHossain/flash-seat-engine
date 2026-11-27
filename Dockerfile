# One Dockerfile for every service:  docker build --build-arg SERVICE=inventory -t flash-seat/inventory .
#
# Stage 1 builds all modules once. It does not depend on SERVICE, so Docker reuses it for every service image.
FROM eclipse-temurin:25.0.5.1_2-jdk-alpine AS build
WORKDIR /src

# Copy only the build files first: dependencies are downloaded once and cached until a pom.xml changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY services/inventory/pom.xml services/inventory/pom.xml
COPY services/order/pom.xml services/order/pom.xml
COPY services/worker/pom.xml services/worker/pom.xml
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q dependency:go-offline

COPY services services
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q package -DskipTests

# Stage 2 splits the chosen service's jar into layers (libraries change rarely, our code often),
# so a code change only re-ships the small top layer.
FROM eclipse-temurin:25.0.5.1_2-jdk-alpine AS layers
ARG SERVICE
WORKDIR /layers
COPY --from=build /src/services/${SERVICE}/target/${SERVICE}-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# Stage 3 is what runs: a JRE only (no compiler, no Maven, no source), as a non-root user.
FROM eclipse-temurin:25.0.5.1_2-jre-alpine
ARG SERVICE
ARG PORT
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./
USER app

ENV PORT=${PORT}
# Size the heap from the container's memory limit instead of the host's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true"
EXPOSE ${PORT}
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- "http://localhost:${PORT}/actuator/health/readiness" > /dev/null || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
