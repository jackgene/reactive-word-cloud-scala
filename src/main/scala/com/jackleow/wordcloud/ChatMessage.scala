package com.jackleow.wordcloud

import spray.json.*

object ChatMessage:
  given jsonFormat: JsonFormat[ChatMessage] = new RootJsonFormat:
    override def read(json: JsValue): ChatMessage =
      json.asJsObject.getFields("s", "r", "t") match
        case Seq(JsString(sender), JsString(recipient), JsString(text)) =>
          ChatMessage(sender, recipient, text)
        case _ => deserializationError("Cannot parse JSON as ChatMessage")
    override def write(chatMessage: ChatMessage): JsValue =
      JsObject(
        "s" -> JsString(chatMessage.sender),
        "r" -> JsString(chatMessage.recipient),
        "t" -> JsString(chatMessage.text)
      )
final case class ChatMessage(
  sender: String,
  recipient: String,
  text: String
):
  override def toString: String = s"$sender to $recipient: $text"
