#!/bin/sh
# 후보 A의 함수 핸들러다. 어댑터의 AWS_LAMBDA_EXEC_WRAPPER가 이 파일을 실행한다.
# 지금 LambdaHandler가 넘기는 로그·TLS 인자를 그대로 옮기고, RIE가 쓰는 8080을 피해 8081로 띄운다.
exec java -cp "./:lib/*" com.example.linksphere.LinkSphereBeApplicationKt \
  --server.port=8081 \
  --server.address=127.0.0.1 \
  --logging.structured.format.console=logstash \
  --logging.structured.json.customizer=com.example.linksphere.global.common.EmailMaskingLogCustomizer \
  --spring.datasource.hikari.data-source-properties.sslmode=require
