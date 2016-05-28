package actor

import akka.actor.{FSM, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, WebSocketRequest, WebSocketUpgradeResponse}
import akka.stream.ActorMaterializer
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import util.PushDestination

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContextExecutor, Future, Promise}

/**
  * Created by victor on 26/05/16.
  */

class WebSocketConnectionActor(
  maxBufferSize: Int,
  pushDestination: PushDestination,
  tickDuration: FiniteDuration
  )(implicit ec: ExecutionContextExecutor) extends FSM[State, Data] {

  private implicit val materializer = ActorMaterializer()
  private val (wTimerName, cTimerName, dTimerName) = ("waiting", "connected", "disconnected")
  private def tickTimerFor(name: String, duration: FiniteDuration = tickDuration) = setTimer(name, Tick, duration, repeat = true)

  private def getPusher = {
    val actor = context.actorOf(Publisher.props(maxBufferSize))
    val sink = Sink.ignore
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
    case Event(Push(m), Empty) =>
      val (actor, connected, canceled) = getPusher
      goto(WFC) using PossibleConnection(Vector(m), actor, connected, canceled)
    case Event(CancelWithRecoveredState(b), Empty) =>
      goto(Disconnected) using Buffer(b)
  }

  when(WFC) {
    case Event(Push(m), PossibleConnection(buf, pusher, connected, canceled)) if canceled.isCompleted =>
      goto(Disconnected) using Buffer(buf appendFixedMaxSize m)
    case Event(Push(m), PossibleConnection(buf, pusher, connected, canceled)) if connected.isCompleted =>
      goto(Connected) using Connection(buf appendFixedMaxSize m, pusher, canceled)
    case Event(Push(m), PossibleConnection(buf, pusher, connected, canceled)) =>
      stay using PossibleConnection(buf appendFixedMaxSize m, pusher, connected, canceled)
    case Event(Tick, _) =>
      stateData match {
        case PossibleConnection(buf, pusher, connected, canceled) if canceled.isCompleted =>
          goto(Disconnected) using Buffer(buf)
        case PossibleConnection(buf, pusher, connected, canceled) if connected.isCompleted =>
          goto(Connected) using Connection(buf, pusher, canceled)
        case _ =>
          stay
      }
    case Event(CancelWithRecoveredState(b), PossibleConnection(buf, pusher, connected, canceled)) =>
      stay using PossibleConnection(b concatFixedMaxSize buf, pusher, connected, canceled)
  }

  when(Connected) {
    case Event(Push(m), Connection(buf, pusher, canceled)) if canceled.isCompleted =>
      goto(Disconnected) using Buffer(buf appendFixedMaxSize m)
    case Event(Push(m), Connection(buf, pusher, canceled)) if buf.isEmpty =>
      pusher ! m
      stay
    case Event(Push(m), Connection(buf, pusher, canceled)) =>
      stay using Connection(buf appendFixedMaxSize m, pusher, canceled)
    case Event(Tick, _) =>
      stateData match {
        case Connection(buf, pusher, canceled) if canceled.isCompleted =>
          goto(Disconnected) using Buffer(buf)
        case Connection(buf, pusher, canceled) if buf.isEmpty =>
          cancelTimer(cTimerName)
          stay
        case Connection(buf, pusher, canceled) =>
          buf.foreach(pusher ! _)
          stay using Connection(Vector.empty, pusher, canceled)
      }
    case Event(CancelWithRecoveredState(b), Connection(buf, pusher, canceled)) =>
      goto(Disconnected) using Buffer(b concatFixedMaxSize buf)
  }

  when(Disconnected) {
    case Event(Push(m), Buffer(buf)) =>
      val (actor, connected, canceled) = getPusher
      goto(WFC) using PossibleConnection(buf appendFixedMaxSize m, actor, connected, canceled)
    case Event(Tick, _) =>
      stateData match {
        case Buffer(buf) if buf.isEmpty =>
          goto(Idle) using Empty
        case Buffer(buf) =>
          val (actor, connected, canceled) = getPusher
          goto(WFC) using PossibleConnection(buf, actor, connected, canceled)
      }
    case Event(CancelWithRecoveredState(b), Buffer(buf)) =>
      stay using Buffer(b concatFixedMaxSize buf)
  }

  onTransition{
    case Idle -> WFC =>
      tickTimerFor(wTimerName)
    case WFC -> Connected =>
      cancelTimer(wTimerName)
      tickTimerFor(cTimerName)
      log.debug("New WebSocket connection established.")
    case WFC -> Disconnected =>
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
    case Disconnected -> WFC =>
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
  def props(mbs: Int, pd: PushDestination, dtd: FiniteDuration)(implicit ctx: ExecutionContextExecutor): Props =
    Props(new WebSocketConnectionActor(mbs, pd, dtd)(ctx))
}
