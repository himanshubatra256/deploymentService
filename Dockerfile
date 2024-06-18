FROM openjdk:17-jdk-alpine

# Install Node.js and npm
RUN apk update && apk add --no-cache nodejs npm git

# Add your JAR and credentials.properties
ADD target/deploymentService.jar deploymentService.jar
ADD target/credentials.properties credsFile/credentials.properties

# Expose port
EXPOSE 8081

# Set environment variable for credentials path
ENV CREDS_VAL="credsFile/"

# Set entry point to run Java application
ENTRYPOINT ["java", "-jar", "deploymentService.jar"]