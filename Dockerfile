### Build stage
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

# Install bash
RUN apk add --no-cache bash

# Copy tool-backend source
COPY . .

# Make mvnw executable
RUN chmod +x mvnw

# Build arguments
ARG GITHUB_TOKEN
ARG GITHUB_ACTOR

# Configure Maven to authenticate with GitHub Packages (if needed)
RUN if [ -n "${GITHUB_TOKEN}" ] && [ -n "${GITHUB_ACTOR}" ]; then \
      echo "Configuring Maven for GitHub Packages..."; \
      mkdir -p ~/.m2 && \
      echo '<settings><servers><server>' > ~/.m2/settings.xml && \
      echo '<id>github</id>' >> ~/.m2/settings.xml && \
      echo "<username>${GITHUB_ACTOR}</username>" >> ~/.m2/settings.xml && \
      echo "<password>${GITHUB_TOKEN}</password>" >> ~/.m2/settings.xml && \
      echo '</server></servers></settings>' >> ~/.m2/settings.xml; \
    fi

# Build the application
RUN ./mvnw clean install -DskipTests

### Runtime stage
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# Set environment variables
ENV SPRING_PROFILES_ACTIVE=production

# Copy the built JAR
COPY --from=builder /app/target/ismd-tool-backend-*.jar app.jar

# Add labels
LABEL org.opencontainers.image.title="ISMD Tool Backend"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
