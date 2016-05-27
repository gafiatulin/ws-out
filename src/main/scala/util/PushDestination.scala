package util

import akka.http.scaladsl.model.Uri

/**
  * Created by victor on 27/05/16.
  */

final case class PushDestination(interface: String, port: Int, path: String){
  def toWSTargetUri: Uri = Uri(s"ws://$interface:$port$path")
}
