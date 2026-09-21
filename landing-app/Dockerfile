# MiruQA ランディング（JSP＋サーブレット）。Tomcat 10.1 / Java 21。ECS Fargate・App Runner で動かす。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM tomcat:10.1-jdk21-temurin
# 既定のWebアプリ（管理画面など）を消して、ROOTだけを置く
RUN rm -rf /usr/local/tomcat/webapps/*
COPY --from=build /src/target/ROOT.war /usr/local/tomcat/webapps/ROOT.war
# 実行ユーザーは、root以外
RUN useradd -r -u 1001 app && chown -R app /usr/local/tomcat
USER app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Tokyo"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --retries=3 CMD curl -fsS http://127.0.0.1:8080/health || exit 1
