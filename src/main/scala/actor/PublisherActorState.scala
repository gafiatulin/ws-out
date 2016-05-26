package actor

import akka.http.scaladsl.model.ws.Message

/**
  * Created by victor on 26/05/16.
  */
final case class PublisherActorState(buffer: Vector[Message])
