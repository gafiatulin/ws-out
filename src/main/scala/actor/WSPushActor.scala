package actor

import akka.actor.{Actor, ActorRef, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, WebSocketRequest}
import akka.stream.ActorMaterializer
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}

import scala.concurrent.Promise

/**
  * Created by victor on 26/05/16.
  */
class WSPushActor extends Actor {
  private implicit val materializer = ActorMaterializer()
  private implicit val ec = context.dispatcher

  private var publisher: ActorRef = context.actorOf(Publisher.props())
  private val MaxBufferSize = 1000
  private var buffer = Vector.empty[Message]

  private var publisherState: Option[Vector[Message]] = None

  override def preStart(): Unit = {
    context.become(unconnected)
  }

  def connected: Receive = {
    case st: PublisherActorState =>
      publisherState = if (st.buffer.isEmpty) None else Some(st.buffer)
    case m: Message =>
      publisher ! m
  }

  def unconnected: Receive = {
    case st: PublisherActorState =>
      publisherState = if (st.buffer.isEmpty) None else Some(st.buffer)
    case m: Message =>
      publisher = context.actorOf(Publisher.props(publisherState))
      val sink = Sink.ignore
      val source = Source.fromPublisher(ActorPublisher[Message](publisher)).concatMat(Source.maybe[Message])(Keep.right)
      val flow: Flow[Message, Message, Promise[Option[Message]]] = Flow.fromSinkAndSourceMat(sink, source)(Keep.right)
      val (upgradeResponse, promise) = Http()(context.system).singleWebSocketRequest(WebSocketRequest("ws://0.0.0.0:2525"), flow)
      if(buffer.size <= MaxBufferSize) buffer :+= m else buffer = buffer.drop(1) :+ m
      upgradeResponse.onSuccess{
        case x if x.response.status.isSuccess =>
          context.become(connected)
          publisherState = None
          val buf = buffer
          buf foreach {v =>
            self ! v
            buffer = buffer.drop(1)
          }
        case _ => ()
      }
      promise.future.onComplete(_ => context.become(unconnected))
    case _ => ()
  }

  def receive = Actor.emptyBehavior
}

