package actor

/**
  * Created by victor on 27/05/16.
  */

sealed trait State
case object Idle extends State
case object WaitingForConnection extends State
case object Connected extends State
case object Disconnected extends State
