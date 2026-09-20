# Build the application from source so a clean repository clone can produce the image.
FROM maven:3.9.9-eclipse-temurin-17-alpine AS builder

WORKDIR /build
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine

LABEL org.opencontainers.image.title="doc-anonymizer" \
      org.opencontainers.image.description="PDF y fotos a Markdown desidentificado, 100% local" \
      org.opencontainers.image.version="0.2.0"

# Tesseract and the Spanish language model are bundled in the runtime image.
RUN apk add --no-cache tesseract-ocr tesseract-ocr-data-spa \
    && addgroup -S anon \
    && adduser -S -G anon -h /app anon

WORKDIR /app
COPY --from=builder --chown=anon:anon /build/target/doc-anonymizer.jar /app/app.jar

# Containers bind externally; Render supplies PORT, while Compose retains the 8080 default.
ENV DOC_ANONYMIZER_HOST=0.0.0.0 \
    TESSERACT_COMMAND=tesseract \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true"

EXPOSE 8080
USER anon

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- "http://127.0.0.1:${DOC_ANONYMIZER_PORT:-${PORT:-8080}}/health" || exit 1

ENTRYPOINT ["java", "-cp", "/app/app.jar", "com.docanonymizer.adapter.web.WebServer"]
