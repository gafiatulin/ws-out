package actor

import akka.http.scaladsl.model.ws.Message

import scala.concurrent.Promise

/**
  * Created by victor on 27/05/16.
  */

sealed trait ActorMessage
case object Tick extends ActorMessage
final case class WebSocketPush(m: Message) extends ActorMessage
final case class CancelWithRecoveredState(buf: Vector[Message]) extends ActorMessage
final case class CancelPromise(p: Promise[Option[Message]]) extends ActorMessage
