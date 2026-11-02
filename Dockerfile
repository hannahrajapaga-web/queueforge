# ---- build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- run stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Render sets PORT; application.properties reads it. 512 MB free instances need a small heap.
ENV JAVA_TOOL_OPTIONS="-Xmx300m -Djava.awt.headless=true"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]