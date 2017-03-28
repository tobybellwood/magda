package au.csiro.data61.magda.registry

import akka.actor.{Actor, ActorRef, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.pattern.ask
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import scalikejdbc._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._

object SendWebHookPayloadActor {
  def props() = Props(new SendWebHookPayloadActor())

  case class Send(webHook: WebHook, payload: RecordsChangedWebHookPayload)
  case class SendEntity(originalSender: ActorRef, webHook: WebHook, payload: RecordsChangedWebHookPayload, entity: MessageEntity)
  case class ReceivePostResponse(originalSender: ActorRef, webHook: WebHook, payload: RecordsChangedWebHookPayload, response: HttpResponse)
}

class SendWebHookPayloadActor extends Actor with Protocols {
  import NotifyOneWebHookActor._
  import SendWebHookPayloadActor._
  import context.dispatcher

  implicit val materializer = ActorMaterializer()(context)
  private val http = Http(context.system)

  def receive = {
    case Send(webHook, payload) => {
      val originalSender = sender()
      Marshal(payload).to[MessageEntity].map(SendEntity(originalSender, webHook, payload, _)).pipeTo(this.self)
    }
    case SendEntity(originalSender, webHook, payload, entity) => http.singleRequest(HttpRequest(
        uri = Uri(webHook.url),
        method = HttpMethods.POST,
        entity = entity
      )).map(ReceivePostResponse(originalSender, webHook, payload, _)).pipeTo(this.self)
    case ReceivePostResponse(originalSender, webHook, payload, response) => {
      // TODO: if we don't get a 200 response, we should retry or something
      response.discardEntityBytes()
      // TODO: doing this synchronously in the Actor is probably bad.  Wrap in a Future?
      DB localTx { session =>
        HookPersistence.setLastEvent(session, webHook.id.get, payload.lastEventId)
      }
      originalSender ! NotificationSent(webHook, payload, response.status.isSuccess())
    }
  }
}

object NotifyAllWebHooksActor {
  def props() = Props(new NotifyAllWebHooksActor())

  case object Start
  case class ProcessWebHooks(originalSender: ActorRef, webHooks: List[WebHook])
  case object Done
}

class NotifyAllWebHooksActor extends Actor {
  import NotifyAllWebHooksActor._
  import NotifyOneWebHookActor._
  import context.dispatcher

  implicit val materializer: ActorMaterializer = ActorMaterializer()(context)

  // TODO: make this a router so we can do multiple in parallel
  val notifyOneActor = context.actorOf(NotifyOneWebHookActor.props, "NotifyOneWebHook")

  val simultaneousInvocations = 6

  def receive = {
    case Start => {
      println("WebHook Processing: STARTING")
      val originalSender = sender()
      Future[ProcessWebHooks] {
        println("Future")
        val webHooks = DB readOnly { implicit session =>
          println("DB")
          val latestEventId = sql"select eventId from Events order by eventId desc limit 1".map(rs => rs.long("eventId")).single.apply().getOrElse(0l)
          HookPersistence.getAll(session).filter(hook => hook.lastEvent.getOrElse(0l) < latestEventId)
        }
        println("got all")
        ProcessWebHooks(originalSender, webHooks)
      }.pipeTo(this.self)
    }
    case ProcessWebHooks(originalSender, webHooks) => {
      if (webHooks.length > 0) {
        println("Processing")
        Source(webHooks).map(Notify(_)).runWith(Sink.actorRefWithAck(notifyOneActor, Start, Ack, Done))
      } else {
        println("WebHook Processing: DONE")
        originalSender ! Done
      }
    }
    case NotificationComplete(webHook, successes, failures) => {
      println("WebHook Processing: DONE")
      //originalSender ! Done
    }
  }
}

class WebHookActor extends Actor with Protocols {
  import context.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()(context)

  private val notifyAllActor = context.actorOf(NotifyAllWebHooksActor.props, "NotifyAllWebHooks")
  private var processAgain = false
  private var isProcessing = false

  def receive = {
    case "process" => {
      if (this.isProcessing) {
        this.processAgain = true
      } else {
        isProcessing = true
        notifyAllActor ! NotifyAllWebHooksActor.Start
      }
    }
    case NotifyAllWebHooksActor.Done => {
      println("WebHookActor done")
      isProcessing = false
      if (this.processAgain) {
        println("Running again!")
        this.processAgain = false
        this.self ! "process"
      }
    }
  }
}
