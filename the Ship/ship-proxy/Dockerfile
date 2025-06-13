FROM openjdk:11-jdk-slim

WORKDIR /app

COPY ShipProxy.java .
COPY compile_and_run.sh .

RUN chmod +x compile_and_run.sh

CMD ["./compile_and_run.sh"]
