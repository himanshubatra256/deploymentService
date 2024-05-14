FROM openjdk:17-jdk-alpine
ADD target/deploymentService.jar deploymentService.jar
ADD target/credentials.properties cpfile/credentials.properties

# Install Git
RUN apk update && apk add --no-cache git
EXPOSE 8081
ENV CREDS_VAL="cpfile/"
ENTRYPOINT ["java", "-jar", "deploymentService.jar"]