package example.akkawschat

import java.util.Date

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.{ Message, TextMessage }

import scala.concurrent.duration._
import akka.http.scaladsl.server.Directives
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import upickle.default._
import shared.Protocol
import shared.Protocol._

import scala.util.Failure

class Webservice(implicit system: ActorSystem) extends Directives {
  val theChat = Chat.create(system)

  implicit val materializer = ActorMaterializer()

  import system.dispatcher

  system.scheduler.schedule(15.second, 15.second) {
    theChat.injectMessage(ChatMessage(sender = "clock", s"Bling! The time is ${new Date().toString}."))
  }

  val (messagesIn: Sink[Message, _], messagesOut: Source[Message, _]) = MergeHub.source[Message]
    .collect { case TextMessage.Strict(msg) => read[Protocol.Message](msg) }
    .map[String](write(_))
    .map[Message](TextMessage.Strict)
    .toMat(BroadcastHub.sink)(Keep.both)
    .run()

  def makeMessageSink(username: String): Sink[Message, _] = {
    /*Flow[Message].watchTermination(){ (_, f) =>
      val leftMessage = write(Left(username, List.empty))
      Source.fromFuture(f).map(_ => TextMessage.Strict(leftMessage)).runWith(messagesIn)
    }.to(messagesIn)*/
    Flow[Message].recover { case x: Throwable => TextMessage.Strict(write(Left(username, List.empty))) }.to(messagesIn)
  }

  def route =
    get {
      pathSingleSlash {
        getFromResource("web/index.html")
      } ~
        // Scala-JS puts them in the root of the resource directory per default,
        // so that's where we pick them up
        path("frontend-launcher.js")(getFromResource("frontend-launcher.js")) ~
        path("frontend-fastopt.js")(getFromResource("frontend-fastopt.js")) ~
        path("chat") {
          parameter('name) { name ⇒
            handleWebSocketMessages(websocketChatFlow(sender = name))
          }
        } ~
        path("chat-stream") {
          parameter('name) { name =>
            extractUpgradeToWebSocket { upgrade =>
              val joinedMessage = write(Joined(name, List.empty))
              Source.single[Message](TextMessage.Strict(joinedMessage)).runWith(messagesIn)
              complete(upgrade.handleMessagesWithSinkSource(makeMessageSink(name), messagesOut))
            }

          }
        }
    } ~
      getFromResourceDirectory("web")

  def websocketChatFlow(sender: String): Flow[Message, Message, Any] =
    Flow[Message]
      .collect {
        case TextMessage.Strict(msg) ⇒ msg // unpack incoming WS text messages...
        // This will lose (ignore) messages not received in one chunk (which is
        // unlikely because chat messages are small) but absolutely possible
        // FIXME: We need to handle TextMessage.Streamed as well.
      }
      .via(theChat.chatFlow(sender)) // ... and route them through the chatFlow ...
      .map {
        case msg: Protocol.Message ⇒
          TextMessage.Strict(write(msg)) // ... pack outgoing messages into WS JSON messages ...
      }
      .via(reportErrorsFlow) // ... then log any processing errors on stdin

  def reportErrorsFlow[T]: Flow[T, T, Any] =
    Flow[T]
      .watchTermination()((_, f) => f.onComplete {
        case Failure(cause) =>
          println(s"WS stream failed with $cause")
        case _ => // ignore regular completion
      })
}
