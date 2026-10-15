FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# Cache do Maven compartilhado entre os servicos (sharing=locked: um build espera o outro, sem downloads simultaneos)
# e novas tentativas: o repositorio central as vezes responde 502 quando varios builds baixam ao mesmo tempo.
RUN --mount=type=cache,id=maven-repo,target=/root/.m2,sharing=locked \
    for i in 1 2 3 4; do mvn -B -q dependency:go-offline && break; \
      [ "$i" -eq 4 ] && exit 1; echo "Maven falhou (tentativa $i), nova tentativa em 15 s"; sleep 15; done
COPY src ./src
RUN --mount=type=cache,id=maven-repo,target=/root/.m2,sharing=locked \
    for i in 1 2 3; do mvn -B -q package -DskipTests && break; \
      [ "$i" -eq 3 ] && exit 1; echo "Maven falhou (tentativa $i), nova tentativa em 15 s"; sleep 15; done

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
