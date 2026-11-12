FROM eclipse-temurin:25-jdk AS build
WORKDIR /project
COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle gradle.properties ./
COPY src/main/ src/main/
RUN sed -i 's/\r$//' gradlew && sh gradlew --no-daemon bootJar && cp build/libs/*-SNAPSHOT.jar /tmp/campus.jar

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --system campus && useradd --system -g campus campus && mkdir -p /var/lib/campus && chown campus:campus /var/lib/campus
COPY --from=build /tmp/campus.jar app.jar
COPY config/portal-links.properties config/portal-links.properties
USER campus
ENV SPRING_PROFILES_ACTIVE=hosted CAMPUS_STORAGE_DIR=/var/lib/campus
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "app.jar"]
