package actor

import akka.actor.{ActorLogging, Props}
import akka.http.scaladsl.model.ws.Message
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}

import scala.annotation.tailrec
import scala.concurrent.Promise

/**
  * Created by victor on 26/05/16.
  */

class Publisher(mbs: Int) extends ActorPublisher[Message] with ActorLogging {
  val maxBufferSize = mbs
  var buffer = Vector.empty[Message]
  var sendWhileInDemand = Vector.empty[Message]

  @tailrec final def pushBuffer(): Unit =
    if (totalDemand > 0) {
      val willBreakSplitAt = totalDemand > Int.MaxValue
      val (send, keep) = buffer splitAt (if (willBreakSplitAt) Int.MaxValue else totalDemand.toInt)
      buffer = keep
      sendWhileInDemand = send
      send foreach {x =>
        sendWhileInDemand = sendWhileInDemand.drop(1)
        onNext(x)
      }
      if (willBreakSplitAt) pushBuffer()
    }

  final private def active(p: Promise[Option[Message]]): Receive = {
    case m: Message if buffer.size == maxBufferSize =>
      buffer = buffer.drop(1) :+ m
      pushBuffer()
    case m: Message =>
      if (buffer.isEmpty && totalDemand > 0) {
        onNext(m)
      }
      else {
        buffer :+= m
        pushBuffer()
      }
    case Request(_) => pushBuffer()
    case Cancel =>
      log.info("Shutting down Publisher with buffer size {}", buffer.size)
      context.parent ! CancelWithRecoveredState(sendWhileInDemand ++ buffer)
      if (!p.isCompleted) p.failure(new Exception)
      context.stop(self)
  }

  final def receive: Receive = {
    case cp: CancelPromise => context.become(active(cp.p))
  }
}

object Publisher{
  def props(mbs: Int): Props = Props(new Publisher(mbs))
}
