# Cloud Run 배포 이미지: gcloud run deploy phonedoctor-api --source server/ (server/README.md)
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon installDist -x test

FROM eclipse-temurin:17-jre
COPY --from=build /src/build/install/phonedoctor-server /app
CMD ["/app/bin/phonedoctor-server"]
