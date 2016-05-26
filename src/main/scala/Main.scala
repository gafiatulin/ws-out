import actor.WSPushActor
import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.model.ws.TextMessage

/**
  * Created by victor on 26/05/16.
  */

object Main extends App {

  implicit val system = ActorSystem()

  val actor = system.actorOf(Props[WSPushActor])

  def f(): Unit = {
    val str = scala.io.StdIn.readLine
    actor ! TextMessage.Strict(str)
    if (str == "exit") () else f()
  }
  f()

}
