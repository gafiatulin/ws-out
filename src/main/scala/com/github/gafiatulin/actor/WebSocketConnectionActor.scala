package com.github.gafiatulin.actor

import akka.actor.{FSM, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, WebSocketRequest, WebSocketUpgradeResponse}
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl.{Flow, Keep, Source}
import akka.stream.{ActorMaterializer, Graph, SinkShape}
import com.github.gafiatulin.util.PushDestination

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContextExecutor, Future, Promise}

/**
  * Created by victor on 26/05/16.
  */

class WebSocketConnectionActor[A](
  maxBufferSize: Int,
  pushDestination: PushDestination,
  tickDuration: FiniteDuration,
  sink: Graph[SinkShape[Message], A]
  )(implicit ec: ExecutionContextExecutor) extends FSM[State, Data] {

  private implicit val materializer = ActorMaterializer()
  private val (wTimerName, cTimerName, dTimerName) = ("waiting", "connected", "disconnected")
  private def tickTimerFor(name: String, duration: FiniteDuration = tickDuration) = setTimer(name, Tick, duration, repeat = true)

  private def getWebSocketPublisher = {
    val actor = context.actorOf(WebSocketPublisherActor.props(maxBufferSize))
    val source = Source.fromPublisher(ActorPublisher[Message](actor)).concatMat(Source.maybe[Message])(Keep.right)
    val flow: Flow[Message, Message, Promise[Option[Message]]] = Flow.fromSinkAndSourceMat(sink, source)(Keep.right)
    val (upgradeResponse, canceled) = Http()(context.system).singleWebSocketRequest(WebSocketRequest(pushDestination.toWSTargetUri), flow)
    actor ! CancelPromise(canceled)
    val connected = upgradeResponse.flatMap{
      case resp: WebSocketUpgradeResponse if resp.response.status.isSuccess => Future.successful(())
      case _ => Future.failed[Unit](new Exception)
    }
    (actor, connected, canceled)
  }

  private final implicit class RichVector[T](vec: Vector[T]){
    def appendFixedMaxSize(v: T): Vector[T] = (if (vec.size < maxBufferSize) vec else vec.drop(1)) :+ v
    def concatFixedMaxSize(other: Vector[T]): Vector[T] = maxBufferSize.toLong match {
      case mbs: Long if other.size.toLong > mbs => other.takeRight(mbs.toInt)
      case mbs: Long if vec.size.toLong + other.size.toLong > mbs => vec.takeRight((mbs - other.size.toLong).toInt) ++ other
      case _ => vec ++ other
    }
  }

  startWith(Idle, Empty)

  when(Idle) {
    case Event(WebSocketPush(m), Empty) =>
      val (actor, connected, canceled) = getWebSocketPublisher
      goto(WaitingForConnection) using ConnectionAttempt(Vector(m), actor, connected, canceled)
    case Event(CancelWithRecoveredState(b), Empty) =>
      goto(Disconnected) using Buffer(b)
  }

  when(WaitingForConnection) {
    case Event(WebSocketPush(m), ConnectionAttempt(buf, publisher, connected, canceled)) if canceled.isCompleted =>
      goto(Disconnected) using Buffer(buf appendFixedMaxSize m)
    case Event(WebSocketPush(m), ConnectionAttempt(buf, publisher, connected, canceled)) if connected.isCompleted =>
      goto(Connected) using Connection(buf appendFixedMaxSize m, publisher, canceled)
    case Event(WebSocketPush(m), ConnectionAttempt(buf, publisher, connected, canceled)) =>
      stay using ConnectionAttempt(buf appendFixedMaxSize m, publisher, connected, canceled)
    case Event(Tick, _) =>
      stateData match {
        case ConnectionAttempt(buf, publisher, connected, canceled) if canceled.isCompleted =>
          goto(Disconnected) using Buffer(buf)
        case ConnectionAttempt(buf, publisher, connected, canceled) if connected.isCompleted =>
          goto(Connected) using Connection(buf, publisher, canceled)
        case _ =>
          stay
      }
    case Event(CancelWithRecoveredState(b), ConnectionAttempt(buf, publisher, connected, canceled)) =>
      stay using ConnectionAttempt(b concatFixedMaxSize buf, publisher, connected, canceled)
  }

  when(Connected) {
    case Event(WebSocketPush(m), Connection(buf, publisher, canceled)) if canceled.isCompleted =>
      goto(Disconnected) using Buffer(buf appendFixedMaxSize m)
    case Event(WebSocketPush(m), Connection(buf, publisher, canceled)) if buf.isEmpty =>
      publisher ! m
      stay
    case Event(WebSocketPush(m), Connection(buf, publisher, canceled)) =>
      stay using Connection(buf appendFixedMaxSize m, publisher, canceled)
    case Event(Tick, _) =>
      stateData match {
        case Connection(buf, publisher, canceled) if canceled.isCompleted =>
          goto(Disconnected) using Buffer(buf)
        case Connection(buf, publisher, canceled) if buf.isEmpty =>
          cancelTimer(cTimerName)
          stay
        case Connection(buf, publisher, canceled) =>
          buf.foreach(publisher ! _)
          stay using Connection(Vector.empty, publisher, canceled)
      }
    case Event(CancelWithRecoveredState(b), Connection(buf, publisher, canceled)) =>
      goto(Disconnected) using Buffer(b concatFixedMaxSize buf)
  }

  when(Disconnected) {
    case Event(WebSocketPush(m), Buffer(buf)) =>
      val (actor, connected, canceled) = getWebSocketPublisher
      goto(WaitingForConnection) using ConnectionAttempt(buf appendFixedMaxSize m, actor, connected, canceled)
    case Event(Tick, _) =>
      stateData match {
        case Buffer(buf) if buf.isEmpty =>
          goto(Idle) using Empty
        case Buffer(buf) =>
          val (actor, connected, canceled) = getWebSocketPublisher
          goto(WaitingForConnection) using ConnectionAttempt(buf, actor, connected, canceled)
      }
    case Event(CancelWithRecoveredState(b), Buffer(buf)) =>
      stay using Buffer(b concatFixedMaxSize buf)
  }

  onTransition{
    case Idle -> WaitingForConnection =>
      tickTimerFor(wTimerName)
    case WaitingForConnection -> Connected =>
      cancelTimer(wTimerName)
      tickTimerFor(cTimerName)
      log.debug("New WebSocket connection established.")
    case WaitingForConnection -> Disconnected =>
      cancelTimer(wTimerName)
      tickTimerFor(dTimerName)
      log.debug("Wasn't able to establish WebSocket connection.")
    case Connected -> Disconnected =>
      cancelTimer(cTimerName)
      tickTimerFor(dTimerName)
      log.debug("WebSocket connection has been closed.")
    case Disconnected -> Idle =>
      cancelTimer(dTimerName)
      log.debug("All messages have been processed. Idling.")
    case Disconnected -> WaitingForConnection =>
      cancelTimer(dTimerName)
      tickTimerFor(wTimerName)
  }

  whenUnhandled {
    case Event(e, s) =>
      log.warning("received unhandled request {} in state {}/{}", e, stateName, s)
      stay
  }

  initialize()

}

object WebSocketConnectionActor{
  def props[T](mbs: Int, pd: PushDestination, dtd: FiniteDuration, sink: Graph[SinkShape[Message], T])(implicit ctx: ExecutionContextExecutor): Props =
    Props(new WebSocketConnectionActor[T](mbs, pd, dtd, sink)(ctx))
}
