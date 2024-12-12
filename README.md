# Reactive Word Cloud - Scala

## Running
```shell
./sbt "~run"
```

## Infrastructure Set Up
Create Kafka topic:
```shell
kafka-topics --bootstrap-server=localhost:9092 --create --topic=word-cloud.chat-message --partitions=2
kafka-configs --bootstrap-server=localhost:9092 --alter --entity-type=topics --entity-name=word-cloud.chat-message --add-config retention.ms=7200000
kafka-topics --bootstrap-server=localhost:9092 --describe --topic=word-cloud.chat-message
```

Delete Kafka topic:
```shell
kafka-topics --bootstrap-server=localhost:9092 --delete --topic=word-cloud.chat-message
```

Observe Kafka topic chat message records:
```shell
kafka-console-consumer --bootstrap-server=localhost:9092 --topic=word-cloud.chat-message
```

Publish chat message records to Kafka topic:
```shell
kafka-console-producer --bootstrap-server=localhost:9092 --topic=word-cloud.chat-message
```
