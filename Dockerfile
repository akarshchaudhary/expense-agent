FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app
COPY mvnw mvnw.cmd pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -q -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
# Tesseract for image OCR (optional - app works without it via text/PDF path)
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/expense-agent-*.jar app.jar
ENV PORT=8081
EXPOSE 8081
CMD ["java", "-jar", "app.jar"]
