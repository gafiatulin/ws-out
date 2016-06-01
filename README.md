# ws-out

Experiments with akka-http, akka-stream, akka-fsm.

Client-side WebSocket connection manager.

Send messages to WebSocketConnectionActor and it will push them through WebSocket connection with back-pressure.

On connection failure it will try to reconnect, while preserving unprocessed messages.

Once all messages have been sent, it will idle until next message.

Simple receiver: [ws-in](https://github.com/gafiatulin/ws-in)
