package com.github.gafiatulin.util

import java.util.concurrent.TimeUnit

import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

/**
  * Created by victor on 27/05/16.
  */
trait Config {

  private val config = ConfigFactory.load()

  private def finiteDuration(key: String) = (for{
    c <- Try(config.getConfig(key))
    v <- Try(c.getLong("value"))
    u <- Try(c.getString("unit"))
    d <- Try(FiniteDuration(v, TimeUnit.valueOf(u)))
  } yield d).getOrElse(throw new Exception(s"Wasn't able to parse Finite Duration for key $key"))

  val pushDestination = (for {
    c  <- Try(config.getConfig("pushDestination"))
    i  <- Try(c.getString("interface"))
    p  <- Try(c.getInt("port"))
    pp <- Try(c.getString("path"))
  } yield PushDestination(interface = i, port = p, path = pp)).getOrElse(throw new Exception("Wasn't able to parse Push Destination"))

  val defaultDuration = finiteDuration("defaultDuration")

  val connectionQueueBufferSize = Try(config.getString("connectionQueueBufferSize")).map(_.toLowerCase).flatMap{
    case "max" => Success(Int.MaxValue)
    case _ => Failure(new Exception)
  } orElse Try(config.getInt("connectionQueueBufferSize")) getOrElse 100

}
