package com.jackleow.wordcloud

import spray.json.*

object Counts:
  given jsonWriter: JsonWriter[Counts] =
    case Counts(countsByWord: Map[String, Int]) =>
      JsObject(countsByWord.view.mapValues(JsNumber(_)).toMap)
final case class Counts(countsByWord: Map[String, Int])
