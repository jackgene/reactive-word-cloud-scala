package com.jackleow.wordcloud

import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.model.ws.TextMessage
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.kafka.scaladsl.Consumer
import org.apache.pekko.kafka.{ConsumerSettings, Subscriptions}
import org.apache.pekko.stream.scaladsl.{BroadcastHub, Flow, Sink}
import spray.json.*

import java.nio.charset.StandardCharsets
import scala.concurrent.ExecutionContext
import scala.util.matching.Regex
import scala.util.{Failure, Success}

val NonLetterPattern: Regex = """[^\p{L}]+""".r
def normalizeText(msg: ChatMessage): SenderAndText =
  SenderAndText(
    msg.sender,
    NonLetterPattern.replaceAllIn(msg.text, " ")
      .trim
      .toLowerCase
  )

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

def splitIntoWords(
  senderAndText: SenderAndText
): Source[SenderAndWord, NotUsed] = Source(
  senderAndText.text
    .split(" ")
    .map(SenderAndWord(senderAndText.sender, _))
    .reverse
)

val MinWordLen: Int = 3
val MaxWordLen: Int = 15
val StopWords: Set[String] = Set(
  "about", "above", "after", "again", "against", "all", "and", "any", "are", "because", "been", "before", "being", "below", "between", "both", "but", "can", "did", "does", "doing", "down", "during", "each", "few", "for", "from", "further", "had", "has", "have", "having", "her", "here", "hers", "herself", "him", "himself", "his", "how", "into", "its", "itself", "just", "me", "more", "most", "myself", "nor", "not", "now", "off", "once", "only", "other", "our", "ours", "ourselves", "out", "over", "own", "same", "she", "should", "some", "such", "than", "that", "the", "their", "theirs", "them", "themselves", "then", "there", "these", "they", "this", "those", "through", "too", "under", "until", "very", "was", "were", "what", "when", "where", "which", "while", "who", "whom", "why", "will", "with", "you", "your", "yours", "yourself", "yourselves"
)
def isValidWord(senderAndWord: SenderAndWord): Boolean =
  val wordLen: Int = senderAndWord.word.length
  MinWordLen <= wordLen && wordLen <= MaxWordLen
    && !StopWords.contains(senderAndWord.word)

val MaxWordsPerSender: Int = 3
def updateWordsForSender(
  wordsBySender: Map[String, Seq[String]],
  senderAndWord: SenderAndWord,
): Map[String, Seq[String]] =
  val oldWords: Seq[String] =
    wordsBySender.getOrElse(senderAndWord.sender, Seq())
  val newWords: Seq[String] =
    (senderAndWord.word +: oldWords).distinct
      .take(MaxWordsPerSender)
  wordsBySender + (senderAndWord.sender -> newWords)

def countWords(
  wordsBySender: Map[String, Seq[String]]
): Map[String, Int] = wordsBySender
  .flatMap:
    case (sender: String, words: Seq[String]) =>
      words.map(_ -> sender)
  .groupMap(_._1)(_._2)
  .view.mapValues(_.size).toMap

@main def startServer(): Unit =
  given system: ActorSystem[Nothing] =
    ActorSystem[Nothing](Behaviors.empty, "reactive-word-cloud")
  given executionContext: ExecutionContext = system.executionContext

  val chatMessages: Source[ChatMessage, _] = Consumer
    .plainSource(
      ConsumerSettings(
        system, new StringDeserializer,
        (_, data: Array[Byte]) => new String(
          data, StandardCharsets.UTF_8
        ).parseJson.convertTo[ChatMessage]
      ).withBootstrapServers("localhost:9092"),
      Subscriptions.topics("word-cloud.chat-message")
    )
    .map(_.value)
    .runWith(BroadcastHub.sink)
  val wordCounts: Source[Counts, _] = chatMessages
    .map(normalizeText)
    .flatMapConcat(splitIntoWords)
    .filter(isValidWord)
    .scan(Map())(updateWordsForSender)
    .map(countWords).map(Counts(_))
  val debuggingWordCounts: Source[DebuggingCounts, _] = chatMessages
    .map: (msg: ChatMessage) =>
      val normalizedText: SenderAndText = normalizeText(msg)
      val words: Seq[SenderAndWord] = normalizedText.text
        .split(" ")
        .map(SenderAndWord(normalizedText.sender, _))

      (msg, normalizedText.text, words.map((word: SenderAndWord) => (word, isValidWord(word))))
    .scan((DebuggingCounts(Seq(), Map()), Map[String, Seq[String]]())):
      case ((accum: DebuggingCounts, oldWordsBySender: Map[String, Seq[String]]), (msg: ChatMessage, normalizedText: String, splitWords: Seq[(SenderAndWord, Boolean)])) =>
        val extractedWords: Seq[DebuggingCounts.Event.ExtractedWord] = splitWords
          .scanRight(DebuggingCounts.Event.ExtractedWord("", false, oldWordsBySender, Map())):
            case ((word: SenderAndWord, isValid: Boolean), extractedWord: DebuggingCounts.Event.ExtractedWord) =>
              if (isValid)
                val newWordsBySender: Map[String, Seq[String]] = updateWordsForSender(oldWordsBySender, word)
                val countsByWord: Map[String, Int] = countWords(newWordsBySender)

                extractedWord.copy(
                  word = word.word,
                  isValid = true,
                  wordsBySender = newWordsBySender,
                  countsByWord = countsByWord
                )
              else extractedWord.copy(word = word.word, isValid = false)
          .drop(1)
          .reverse
        (
          accum.copy(
            history = accum.history :+ DebuggingCounts.Event(
              msg, normalizedText, extractedWords
            ),
            countsByWord = extractedWords.headOption.map(_.countsByWord).getOrElse(Map())
          ),
          extractedWords.headOption.map(_.wordsBySender).getOrElse(Map())
        )
    .map((counts: DebuggingCounts, _) => counts)
  Http()
    .newServerAt("0.0.0.0", 9673)
    .bind:
      pathSingleSlash:
        get:
          parameter("debug".as[Boolean].withDefault(false)): (debug: Boolean) =>
            handleWebSocketMessages(
              Flow.fromSinkAndSource(
                Sink.ignore,
                if (!debug) wordCounts.map: (counts: Counts) =>
                  TextMessage(counts.toJson.compactPrint)
                else debuggingWordCounts.map: (counts: DebuggingCounts) =>
                  TextMessage(counts.toJson.compactPrint)
              )
            )
    .onComplete:
      case Success(binding: ServerBinding) =>
        val addr = binding.localAddress
        system.log.info(s"Server online at http://0.0.0.0:${addr.getPort}/")
      case Failure(e: Throwable) =>
        system.log.error("Failed to bind HTTP endpoint, terminating system", e)
        system.terminate()
