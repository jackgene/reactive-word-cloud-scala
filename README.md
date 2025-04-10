# Reactive Word Cloud - Scala

## Running
```shell
./sbt "~run"
```

## Infrastructure Set Up
Run Kafka:
```shell
export CONTAINER_CMD=docker # or podman
$CONTAINER_CMD run -d --name=kafka -p 9092:9092 apache/kafka
```

Create Kafka topic:
```shell
$CONTAINER_CMD exec -ti kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server=localhost:9092 --create --topic=word-cloud.chat-message --partitions=2
$CONTAINER_CMD exec -ti kafka /opt/kafka/bin/kafka-configs.sh --bootstrap-server=localhost:9092 --alter --entity-type=topics --entity-name=word-cloud.chat-message --add-config retention.ms=7200000
$CONTAINER_CMD exec -ti kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server=localhost:9092 --describe --topic=word-cloud.chat-message
```

Delete Kafka topic:
```shell
$CONTAINER_CMD exec -ti kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server=localhost:9092 --delete --topic=word-cloud.chat-message
```

Observe Kafka topic chat message records:
```shell
$CONTAINER_CMD exec -ti kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server=localhost:9092 --topic=word-cloud.chat-message
```

Publish chat message records to Kafka topic:
```shell
$CONTAINER_CMD exec -ti kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server=localhost:9092 --topic=word-cloud.chat-message
```
