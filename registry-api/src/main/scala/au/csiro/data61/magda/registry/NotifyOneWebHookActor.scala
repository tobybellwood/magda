package au.csiro.data61.magda.registry

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.ask
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Future

object NotifyOneWebHookActor {
  def props() = Props(new TheActor())

  case object Start
  case object Ack
  case object Done
  case class Notify(webHook: WebHook)
  case class NotificationSent(webHook: WebHook, payload: RecordsChangedWebHookPayload, success: Boolean)
  case class NotificationComplete(webHook: WebHook, successes: Int, failures: Int)

  class TheActor extends Actor {
    import SendWebHookPayloadActor._
    import context.dispatcher

    private implicit val materializer = ActorMaterializer()(context)
    private implicit val timeout = Timeout(10.seconds)

    private val maxEvents = 100 // TODO: get this from config or something
    private val payloadSender = context.actorOf(SendWebHookPayloadActor.props, "WebHookPlayloadSender")
    private var successes = 0
    private var failures = 0
    private var originalSender: ActorRef = null

    def receive = {
      case Start => sender() ! Ack
      case Done => Unit
      case Notify(webHook) => {
        println("Notify")
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
        this.originalSender ! Ack
        this.originalSender ! NotificationComplete(webHook, successes, failures)
      }
      case NotificationSent(_, _, success) => if (success) this.successes += 1 else this.failures += 1
    }
  }
}
