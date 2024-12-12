package com.jackleow.wordcloud

import spray.json.*

object DebuggingCounts extends DefaultJsonProtocol:
  object Event:
    object ExtractedWord:
      given jsonFormat: JsonFormat[ExtractedWord] =
        jsonFormat4(ExtractedWord.apply)
    final case class ExtractedWord(
      word: String,
      isValid: Boolean,
      wordsBySender: Map[String, Seq[String]],
      countsByWord: Map[String, Int],
    )

    given jsonFormat: JsonFormat[Event] =
      jsonFormat3(Event.apply)
  final case class Event(
    chatMessage: ChatMessage,
    normalizedText: String,
    words: Seq[Event.ExtractedWord],
  )

  given jsonWriter: JsonWriter[DebuggingCounts] =
    jsonFormat2(DebuggingCounts.apply)
final case class DebuggingCounts(
  history: Seq[DebuggingCounts.Event],
  countsByWord: Map[String, Int],
)
