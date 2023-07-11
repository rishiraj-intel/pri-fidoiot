# Copyright 2022 Intel Corporation
# SPDX-License-Identifier: Apache 2.0

FROM ubuntu:20.04
ARG _JAVA_OPTIONS
ENV _JAVA_OPTIONS=${_JAVA_OPTIONS}
RUN apt-get update 
RUN apt-get install -y openjdk-11-jdk

RUN useradd -ms /bin/bash fdo
WORKDIR /home/fdo/
RUN mkdir -p /home/fdo/lib /home/fdo/WEB-INF /home/fdo/app-data

# Setup service dependencies
COPY ./lib ./lib/
COPY ./WEB-INF ./WEB-INF
COPY log4j2.xml .
COPY aio.jar .
COPY hibernate.cfg.xml .
COPY service.yml .
COPY context.xml .
COPY tomcat-users.xml .
COPY secrets.run.path /home/fdo/secrets.path
COPY ./secrets/ca-cert.pem /home/fdo/ca-cert.pem
RUN keytool -import -trustcacerts -noprompt -keystore  /usr/lib/jvm/java-11-openjdk-amd64/lib/security/cacerts -storepass changeit -alias ownercacert -file /home/fdo/ca-cert.pem
COPY config /home/fdo/app-data/config
RUN chown -R fdo:fdo /home/fdo
USER fdo

# Configure and start all-in-one
CMD ["/usr/lib/jvm/java-11-openjdk-amd64/bin/java", "-jar", "aio.jar"]
