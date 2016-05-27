package util

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.Http.HostConnectionPool
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.Flow
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Random, Success, Try}

/**
  * Created by victor on 27/05/16.
  */
trait Config {

  sealed trait Mode
  case object HTTP extends Mode
  case object WS extends Mode

  private val config = ConfigFactory.load()
  type HttpFlow = Flow[(HttpRequest, Int), (Try[HttpResponse], Int), HostConnectionPool]

  private def finiteDuration(key: String) = (for{
    c <- Try(config.getConfig(key))
    v <- Try(c.getLong("val"))
    u <- Try(c.getString("unit"))
    d <- Try(FiniteDuration(v, TimeUnit.valueOf(u)))
  } yield d).getOrElse(throw new Exception(s"Wasn't able to parse Finite Duration for key $key"))

  val mode = Try(config.getString("mode")).flatMap{
    case "http" => Success(HTTP)
    case "ws" => Success(WS)
    case _ => Failure(new Exception)
  }.getOrElse(throw new Exception("Wasn't able to parse Mode. Supported modes: \"ws\" and \"http\""))
  val realData = config.getBoolean("realData")
  val pushDestination = (for {
    c  <- Try(config.getConfig("pushDestination"))
    i  <- Try(c.getString("interface"))
    p  <- Try(c.getInt("port"))
    pp <- Try(c.getString("path"))
  } yield PushDestination(interface = i, port = p, path = pp)).getOrElse(throw new Exception("Wasn't able to parse Push Destination"))
  val length = config.getInt("length")
  val randomDds = Vector.fill(length)(0).foldLeft(Set.empty[String]){ case (s, _) =>
    def f: Set[String] = {
      val n = Math.abs(Random.nextLong).toString
      if (s.contains(n)) f else s + n
    }
    f
  }.toVector
  val defaultDuration = finiteDuration("defaultDuration")
  val fastDuration = finiteDuration("fastDuration")
}
