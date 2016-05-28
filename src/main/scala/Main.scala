import java.util.concurrent.Executors

import actor.{WebSocketPush, WebSocketConnectionActor}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import util.Config

import scala.concurrent.ExecutionContext
import scala.util.Random

/**
  * Created by victor on 26/05/16.
  */

object Main extends App with Config {
  private implicit val system = ActorSystem()
  private implicit val materializer = ActorMaterializer()
  private implicit val ctx = ExecutionContext.fromExecutor(Executors.newCachedThreadPool)

  final case class PushIterator(n: Long) extends Iterator[String] {
    private var count = 0
    def hasNext: Boolean = count < n
    def next: String = {
      count += 1
      if(realData) randomDds(Random.nextInt(length)) else count.toString
    }
  }

  private def askForN(): Unit = system.log.info("Please type the number of messages:")

  private def ask(flow: HttpFlow, req: HttpRequest): Unit = {
    Source.single(req -> Random.nextInt).via(flow).runWith(Sink.head).map(_._1)
  }.foreach(_ => ())

  private def runWith(f: String => Unit): Unit = {
    val it = PushIterator(scala.io.StdIn.readLong)
    it.foreach{x =>
      f(x)
      if(it.hasNext) () else {
        askForN()
        runWith(f)
      }
    }
  }

  system.log.info("Running in {} mode", mode)
  askForN()
  mode match {
    case WS =>
      val actor = system.actorOf(WebSocketConnectionActor.props(Int.MaxValue, pushDestination, defaultDuration))
      runWith(s => actor ! WebSocketPush(TextMessage(s)))
    case HTTP =>
      val cp = Http().cachedHostConnectionPool[Int](host = pushDestination.interface, port = pushDestination.port)
      runWith(s => ask(cp, HttpRequest(uri = Uri(s"/$s"))))
  }

}
