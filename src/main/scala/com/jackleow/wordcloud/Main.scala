package com.jackleow.wordcloud

import com.jackleow.wordcloud.support.BroadcastActor
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.model.ws.TextMessage
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.kafka.scaladsl.Consumer
import org.apache.pekko.kafka.{ConsumerSettings, Subscriptions}
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.{BroadcastHub, Flow, Sink}
import org.apache.pekko.stream.typed.scaladsl.{ActorSink, ActorSource}
import spray.json.*

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
): Map[String, Int] = wordsBySender.toSeq
  .flatMap:
    case (sender: String, words: Seq[String]) =>
      words.map(_ -> sender)
  .groupMap(_._1)(_._2)
  .view.mapValues(_.size).toMap

@main def startServer(): Unit =
  ActorSystem[Nothing](
    Behaviors.setup: (ctx: ActorContext[Nothing]) =>
      given system: ActorSystem[Nothing] = ctx.system
      given executionContext: ExecutionContext = system.executionContext

      val chatMessages: Source[ChatMessage, _] = Consumer
        .plainSource(
          ConsumerSettings(
            system, new StringDeserializer, new StringDeserializer
          )
            .withBootstrapServers("localhost:9092")
            .withGroupId("word-cloud-app")
            .withProperty("auto.offset.reset", "earliest"),
          Subscriptions.topics("word-cloud.chat-message")
        )
        .map(_.value.parseJson.convertTo[ChatMessage])
        .runWith(BroadcastHub.sink)
      val wordCountsBroadcaster: ActorRef[BroadcastActor.Command[Counts]] =
        ctx.spawn(BroadcastActor[Counts](), "word-count-broadcaster")
      chatMessages
        .map(normalizeText)
        .flatMapConcat(splitIntoWords)
        .filter(isValidWord)
        .scan(Map())(updateWordsForSender)
        .map(countWords)
        .map(counts => BroadcastActor.Command.Broadcast(Counts(counts)))
        .runWith(
          ActorSink.actorRef(
            wordCountsBroadcaster,
            onCompleteMessage = BroadcastActor.Command.Stop(None),
            onFailureMessage = _ => BroadcastActor.Command.Stop(None)
          )
        )
      val debuggingWordCountsBroadcaster: ActorRef[BroadcastActor.Command[DebuggingCounts]] =
        ctx.spawn(BroadcastActor[DebuggingCounts](), "debugging-word-count-broadcaster")
      chatMessages
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
                    val newWordsBySender: Map[String, Seq[String]] = updateWordsForSender(extractedWord.wordsBySender, word)
                    val countsByWord: Map[String, Int] = countWords(newWordsBySender)

                    extractedWord.copy(
                      word = word.word,
                      isValid = true,
                      wordsBySender = newWordsBySender,
                      countsByWord = countsByWord
                    )
                  else extractedWord.copy(word = word.word, isValid = false)
              .dropRight(1)
            (
              accum.copy(
                history = accum.history :+ DebuggingCounts.Event(
                  msg, normalizedText, extractedWords
                ),
                countsByWord = extractedWords.headOption.map(_.countsByWord).getOrElse(Map())
              ),
              extractedWords.headOption.map(_.wordsBySender).getOrElse(Map())
            )
        .map((counts: DebuggingCounts, _) => BroadcastActor.Command.Broadcast(counts))
        .runWith(
          ActorSink.actorRef(
            debuggingWordCountsBroadcaster,
            onCompleteMessage = BroadcastActor.Command.Stop(None),
            onFailureMessage = _ => BroadcastActor.Command.Stop(None)
          )
        )
      Http()
        .newServerAt("0.0.0.0", 9673)
        .bind:
          pathSingleSlash:
            get:
              parameter("debug".as[Boolean].withDefault(false)): (debug: Boolean) =>
                handleWebSocketMessages(
                  Flow.fromSinkAndSource(
                    Sink.ignore,
                    if !debug then
                      val (actorRef, source) = ActorSource
                        .actorRef[Counts](PartialFunction.empty, PartialFunction.empty, 1, OverflowStrategy.dropBuffer)
                        .preMaterialize()
                      wordCountsBroadcaster ! BroadcastActor.Command.Subscribe(actorRef)
                      source.map: (counts: Counts) =>
                        TextMessage(counts.toJson.compactPrint)
                    else
                      val (actorRef, source) = ActorSource
                        .actorRef[DebuggingCounts](PartialFunction.empty, PartialFunction.empty, 1, OverflowStrategy.dropBuffer)
                        .preMaterialize()
                      debuggingWordCountsBroadcaster ! BroadcastActor.Command.Subscribe(actorRef)
                      source.map: (counts: DebuggingCounts) =>
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
      Behaviors.empty,
    "reactive-word-cloud"
  )
