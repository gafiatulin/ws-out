package com.github.gafiatulin

import java.util.concurrent.Executors

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.TextMessage
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import com.github.gafiatulin.actor.{WebSocketConnectionActor, WebSocketPush}
import com.github.gafiatulin.util.Config

import scala.concurrent.ExecutionContext

/**
  * Created by victor on 26/05/16.
  */

object Main extends App with Config {
  private implicit val system = ActorSystem()
  private implicit val materializer = ActorMaterializer()

  private implicit val ctx = ExecutionContext.fromExecutor(Executors.newCachedThreadPool)

  private def runWith(f: String => Unit): Unit = {
    val num = scala.io.StdIn.readInt

    (1 to num).foreach(i => f(i.toString))

    runWith(f)
  }
  val wsConnectionActor = system.actorOf(WebSocketConnectionActor.props(connectionQueueBufferSize, pushDestination, defaultDuration, Sink.ignore))

  runWith(s => wsConnectionActor ! WebSocketPush(TextMessage(s)))

}
