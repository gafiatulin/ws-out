package actor

import akka.actor.Props
import akka.http.scaladsl.model.ws.Message
import akka.stream.actor.{ActorPublisher, ActorPublisherMessage}

import scala.annotation.tailrec

/**
  * Created by victor on 26/05/16.
  */

class Publisher(oldState: Option[Vector[Message]]) extends ActorPublisher[Message]{
  import ActorPublisherMessage._
  val MaxBufferSize = 1000
  var buffer = oldState getOrElse Vector.empty[Message]

  @tailrec final def pushBuffer(): Unit =
    if (totalDemand > 0) {
      val willBreakSplitAt = totalDemand > Int.MaxValue
      val (send, keep) = buffer splitAt (if (willBreakSplitAt) Int.MaxValue else totalDemand.toInt)
      buffer = keep
      send foreach onNext
      if (willBreakSplitAt) pushBuffer() else ()
    }

  final def receive = {
    case m: Message if buffer.size == MaxBufferSize => ()
    case m: Message =>
      if (buffer.isEmpty && totalDemand > 0)
        onNext(m)
      else {
        buffer :+= m
        pushBuffer()
      }
    case Request(_) => pushBuffer()
    case Cancel =>
      context.parent ! PublisherActorState(buffer)
      context.stop(self)
  }
}

object Publisher{
  def props(state: Option[Vector[Message]] = None): Props = Props(new Publisher(state))
}