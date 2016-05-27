import java.util.concurrent.Executors

import actor.{Push, WSPushActor}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import util.Config

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Random, Try}

/**
  * Created by victor on 26/05/16.
  */

object Main extends App with Config {

  implicit val system = ActorSystem()
  lazy implicit val materializer = ActorMaterializer()

  private implicit val ctx = ExecutionContext.fromExecutor(Executors.newCachedThreadPool)

  def ask(flow: HttpFlow, req: HttpRequest): Future[Try[HttpResponse]] = {
    Source.single(req -> Random.nextInt).via(flow).runWith(Sink.head).map(_._1)
  }

  mode match {
    case WS =>
      val actor = system.actorOf(WSPushActor.props(Int.MaxValue, pushDestination, defaultDuration, fastDuration))
      while (true) {
        val num = scala.io.StdIn.readInt
        val vector = Vector.fill[Boolean](num)(true)
        (if(realData) vector.map(_ => randomDds(Random.nextInt(length))) else vector.map(_.toString)).foreach{ p =>
          actor ! Push(TextMessage(p))
        }
      }
    case HTTP =>
      val cp = Http().cachedHostConnectionPool[Int](host = pushDestination.interface, port = pushDestination.port)
      while (true) {
        val num = scala.io.StdIn.readInt
        val vector = Vector.fill[Boolean](num)(true)
        (if(realData) vector.map(_ => randomDds(Random.nextInt(length))) else vector.map(_.toString)).foreach{ p =>
          ask(cp, HttpRequest(uri = Uri("segment")))
        }
      }
  }

}
