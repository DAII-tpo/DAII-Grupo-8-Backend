# ---- Build ----
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Primero solo los archivos de build: esta capa queda cacheada mientras no cambien
# las dependencias, asi los deploys que solo tocan codigo no vuelven a descargar todo.
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon --quiet || true

COPY src src
RUN ./gradlew bootJar -x test --no-daemon

# ---- Runtime ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080

# MaxRAMPercentage evita que la JVM se pase del limite de memoria del contenedor
# (importante en planes chicos: por defecto asume que toda la RAM de la maquina es suya).
ENTRYPOINT ["sh", "-c", "java -XX:MaxRAMPercentage=75 -jar app.jar"]
