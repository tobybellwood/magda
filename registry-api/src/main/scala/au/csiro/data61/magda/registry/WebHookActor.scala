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

object NotifyOneWebHookActor {
  def props() = Props(new NotifyAllWebHooksActor())

  case object StartOne
  case object AckOne
  case class Notify(webHook: WebHook)
  case class NotificationSent(webHook: WebHook, payload: RecordsChangedWebHookPayload, success: Boolean)
  case class NotificationComplete(webHook: WebHook, successes: Int, failures: Int)
  case object DoneOne
}

class NotifyOneWebHookActor extends Actor {
  import SendWebHookPayloadActor._
  import NotifyOneWebHookActor._
  import context.dispatcher

  implicit val materializer = ActorMaterializer()(context)

  val maxEvents = 100 // TODO: get this from config or something
  val payloadSender = context.actorOf(SendWebHookPayloadActor.props, "WebHookPlayloadSender")
  implicit val timeout = Timeout(10.seconds)
  var successes = 0
  var failures = 0
  var originalSender: ActorRef = null

  def receive = {
    case StartOne => sender() ! AckOne
    case DoneOne => sender() ! AckOne
    case Notify(webHook) => {
      this.originalSender = sender()
      val events = EventPersistence.streamEventsSince(webHook.lastEvent.get)
      events.grouped(maxEvents).map(events => {
        val relevantEvents: Set[EventType] = Set(EventType.CreateRecord, EventType.CreateRecordAspect, EventType.DeleteRecord, EventType.PatchRecord, EventType.PatchRecordAspect)
        val changeEvents = events.filter(event => relevantEvents.contains(event.eventType))
        val recordIds = changeEvents.map(event => event.eventType match {
          case EventType.CreateRecord | EventType.DeleteRecord | EventType.PatchRecord => event.data.fields("id").toString()
          case _ => event.data.fields("recordId").toString()
        }).toSet
        RecordsChangedWebHookPayload(
          action = "records.changed",
          lastEventId = events.last.id.get,
          events = changeEvents.toList,
          records = recordIds.map(id => Record(id, id, Map())).toList
        )
      }).mapAsync(2)(payload => // 2 to make sure the next one is already in the actor's queue when the previous one finishes
        (payloadSender ? Send(webHook, payload)).mapTo[NotificationSent].pipeTo(this.self))
      .runForeach(_ => Unit)
      .map(_ => NotificationComplete(webHook, this.successes, this.failures))
      .pipeTo(this.self)
    }
    case NotificationComplete(webHook, successes, failures) => {
      println("Notification complete")
      this.originalSender ! AckOne
      this.originalSender ! NotificationComplete(webHook, successes, failures)
    }
    case NotificationSent(_, _, success) => if (success) this.successes += 1 else this.failures += 1
  }
}

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
      // TODO: doing this syncronously in the Actor is probably bad.  Wrap in a Future?
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
        Source(webHooks).map(Notify(_)).runWith(Sink.actorRefWithAck(notifyOneActor, StartOne, AckOne, DoneOne))
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
      isProcessing = false
      if (this.processAgain) {
        this.processAgain = false
        this.self ! "process"
      }
    }
  }
}
