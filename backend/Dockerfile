# 多阶段构建：第一阶段用 Maven 完成依赖下载与打包（首次构建需联网拉依赖，约 5-10 分钟；
# BuildKit 的 cache mount 会缓存 ~/.m2，二次构建显著加速），第二阶段只带 JRE 运行 jar。
# 本地快速路径：已 mvn package 出 target/backend-0.0.1-SNAPSHOT.jar 时，
# 可直接用第 30 行起的"jar 直拷"精简镜像（跳过构建阶段）。
# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# 先拷 pom 单独下依赖：源码变更时利用层缓存，依赖层不重拉
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
# 容器内优雅停机与时区
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
COPY --from=build /build/target/backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 10001
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
