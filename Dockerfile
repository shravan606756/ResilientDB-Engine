FROM eclipse-temurin:21-jdk-jammy

# Install all 3 database utility tools
RUN apt-get update && apt-get install -y wget gnupg2 lsb-release \
    # 1. PostgreSQL 16 Client
    && echo "deb http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list \
    && wget --quiet -O - https://www.postgresql.org/media/keys/ACCC4CF8.asc | apt-key add - \
    # 2. MongoDB Tools
    && wget -qO- https://www.mongodb.org/static/pgp/server-7.0.asc | gpg --dearmor > /etc/apt/trusted.gpg.d/mongodb-server-7.0.gpg \
    && echo "deb [ arch=amd64,arm64 ] https://repo.mongodb.org/apt/ubuntu jammy/mongodb-org/7.0 multiverse" > /etc/apt/sources.list.d/mongodb-org-7.0.list \
    # 3. Final Install
    && apt-get update && apt-get install -y \
       postgresql-client-16 \
       mysql-client \
       mongodb-database-tools \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY target/*.jar app.jar
RUN mkdir -p backups
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]