package actor

import akka.actor.ActorRef
import akka.http.scaladsl.model.ws.Message

import scala.concurrent.{Future, Promise}

/**
  * Created by victor on 27/05/16.
  */

sealed trait Data
case object Empty extends Data
final case class PossibleConnection(buf: Vector[Message], pusher: ActorRef, connected: Future[_], canceled: Promise[_]) extends Data
final case class Connection(buf: Vector[Message], pusher: ActorRef , canceled: Promise[_]) extends Data
final case class Buffer(buf: Vector[Message]) extends Data
