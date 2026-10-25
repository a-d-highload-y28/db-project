from gradle:9.6.0-jdk21 as build

workdir /workdir

copy ./ /workdir

run gradle bootJar

from amazoncorretto:21-headless

workdir /app

copy --from=build /workdir/build/libs/a_d_highload-y28-1.0-SNAPSHOT.jar /app

env SERVER_PORT=8080
env DB_DIRECTORY=/var/lib/tsdb

expose 8080

entrypoint ["java", "-jar", "/app/a_d_highload-y28-1.0-SNAPSHOT.jar"]
