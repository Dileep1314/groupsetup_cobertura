FROM java:8-jre-alpine

ARG commit=local
ARG branch=local
ARG buildName=local
ARG buildNumber=local

LABEL ci.git.commit.id=${commit}
LABEL ci.git.branch=${branch}
LABEL ci.build.name=${buildName}
LABEL ci.build.number=${buildNumber}

VOLUME /tmp
EXPOSE 8080
ADD build/libs/smd-gssp-groupsetup-service-ci.jar app.jar
ADD build/smd-gssp-groupsetup-service-ci-src.tgz /gssp
RUN sh -c 'touch /app.jar'
ENTRYPOINT exec java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -Ddeploy.path=/gssp -jar /app.jar
