package com.jackleow.wordcloud.support

import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

object BroadcastActor:
  enum Command[T]:
    case Broadcast(value: T)
    case Subscribe(subscriber: ActorRef[T])
    case Unsubscribe(subscriber: ActorRef[T])
    case Stop(nothing: Option[T] = None)

  private def awaitingValue[T](
    subscribers: Set[ActorRef[T]]
  ): Behavior[Command[T]] = Behaviors.receive: (ctx: ActorContext[Command[T]], cmd: Command[T]) =>
    cmd match
      case Command.Broadcast(value: T) =>
        for (subscriber: ActorRef[T] <- subscribers)
          subscriber ! value
        running(subscribers, value)

      case Command.Subscribe(subscriber: ActorRef[T]) =>
        ctx.watchWith(subscriber, Command.Unsubscribe(subscriber))
        awaitingValue(subscribers + subscriber)

      case Command.Unsubscribe(subscriber: ActorRef[T]) =>
        ctx.unwatch(subscriber)
        awaitingValue(subscribers - subscriber)

      case Command.Stop(_) =>
        Behaviors.stopped

  private def running[T](
    subscribers: Set[ActorRef[T]], value: T
  ): Behavior[Command[T]] = Behaviors.receive: (ctx: ActorContext[Command[T]], cmd: Command[T]) =>
    cmd match
      case Command.Broadcast(newValue: T) =>
        for (subscriber: ActorRef[T] <- subscribers)
          subscriber ! newValue
        running(subscribers, newValue)

      case Command.Subscribe(subscriber: ActorRef[T]) =>
        ctx.watchWith(subscriber, Command.Unsubscribe(subscriber))
        subscriber ! value
        running(subscribers + subscriber, value)

      case Command.Unsubscribe(subscriber: ActorRef[T]) =>
        ctx.unwatch(subscriber)
        running(subscribers - subscriber, value)

      case Command.Stop(_) =>
        Behaviors.stopped

  def apply[T](): Behavior[Command[T]] = awaitingValue[T](Set())
