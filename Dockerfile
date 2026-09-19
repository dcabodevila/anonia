# Imagen de ejecucion. El jar se construye FUERA y se copia ya hecho.
#
# No es una compilacion multi-etapa a proposito: el briefing exige poder entregar un
# bundle versionado a un entorno aislado, y para eso el artefacto tiene que existir y
# poder auditarse antes de meterlo en la imagen. Compilar dentro obligaria al contenedor
# a bajarse medio Maven Central en cada build.
#
#   mvn -o clean package
#   docker build -t doc-anonymizer:0.2.0 .

FROM eclipse-temurin:17-jre-alpine

LABEL org.opencontainers.image.title="doc-anonymizer" \
      org.opencontainers.image.description="PDF nativo a Markdown desidentificado, 100% local" \
      org.opencontainers.image.version="0.2.0"

# Usuario sin privilegios: el proceso analiza ficheros que vienen de fuera y son,
# por definicion, contenido no confiable.
RUN addgroup -S anon && adduser -S -G anon -h /app anon

WORKDIR /app
COPY --chown=anon:anon target/doc-anonymizer.jar /app/app.jar

# 0.0.0.0 dentro del contenedor: el aislamiento lo da la red de Docker y el puerto
# publicado. Fuera de un contenedor el valor por defecto sigue siendo 127.0.0.1.
ENV DOC_ANONYMIZER_HOST=0.0.0.0 \
    DOC_ANONYMIZER_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true"

EXPOSE 8080
USER anon

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["java", "-cp", "/app/app.jar", "com.docanonymizer.adapter.web.WebServer"]
