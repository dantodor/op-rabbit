package com.spingo.op_rabbit.helpers

import akka.actor.Actor
import akka.actor.Props
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import com.rabbitmq.client.Channel
import com.rabbitmq.client.impl.recovery.AutorecoveringConnection
import com.spingo.op_rabbit.RabbitControl
import com.spingo.op_rabbit.{MessageForPublicationLike, RabbitMarshaller, RabbitUnmarshaller}
import com.spingo.scoped_fixtures.ScopedFixtures
import com.thenewmotion.akka.rabbitmq.{ChannelActor, CreateChannel}
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}

trait RabbitTestHelpers extends ScopedFixtures {
  implicit val timeout = Timeout(5 seconds)
  val killConnection = new MessageForPublicationLike {
    val dropIfNoChannel = true
    def apply(c: Channel): Unit = {
      c.getConnection.close()
    }
  }

  val actorSystemFixture = ScopedFixture[ActorSystem] { setter =>
    val actorSystem = ActorSystem("test")
    val status = setter(actorSystem)
    actorSystem.shutdown
    status
  }
  val rabbitControlFixture = LazyFixture[ActorRef] {
    actorSystemFixture().actorOf(Props[RabbitControl])
  }

  implicit def actorSystem = actorSystemFixture()
  def rabbitControl = rabbitControlFixture()
  def await[T](f: Future[T], duration: Duration = timeout.duration) =
    Await.result(f, duration)

  // kills the rabbitMq connection in such a way that the system will automatically recover and reconnect;
  // synchronously waits for the connection to be terminated, and to be re-established
  def reconnect(rabbitMqControl: ActorRef)(implicit actorSystem: ActorSystem): Unit = {
    /* reconnect autoRecovering connection */
    val connection = await((rabbitMqControl ? RabbitControl.GetConnection).mapTo[AutorecoveringConnection])

    val recovered = Promise[Unit]
    val listener = new com.rabbitmq.client.RecoveryListener {
      def handleRecovery(recoverable: com.rabbitmq.client.Recoverable): Unit =
        recovered.success()
    }
    connection.addRecoveryListener(listener)
    com.rabbitmq.tools.Host.closeConnection(connection)
    await(recovered.future, 30 seconds)
    connection.removeRecoveryListener(listener)

    /* reconnect connectionActor - phasing this out */
    val connectionActor = await((rabbitMqControl ? RabbitControl.GetConnectionActor).mapTo[ActorRef])

    val done = Promise[Unit]
    actorSystem.actorOf(Props(new Actor {
      import akka.actor.FSM._
      override def preStart: Unit = {
        connectionActor ! SubscribeTransitionCallBack(self)
      }

      override def postStop: Unit = {
        connectionActor ! UnsubscribeTransitionCallBack(self)
      }

      def receive = {
        case CurrentState(ref, state) if state.toString == "Connected" =>
          rabbitMqControl ! killConnection
          // because the connection shutdown cause is seen as isInitiatedByApplication, we need to send the Connect signal again to wake things back up.
          context.become {
            case Transition(ref, from, to) if from.toString == "Connected" && to.toString == "Disconnected" =>
              connectionActor ! com.thenewmotion.akka.rabbitmq.ConnectionActor.Connect
              context.become {
                case Transition(ref, from, to) if from.toString == "Disconnected" && to.toString == "Connected" =>
                  done.success()
                  context stop self
              }
          }
      }
    }))

    await(done.future)
  }

  def deleteQueue(queueName: String): Unit = {
    val deleteQueue = DeleteQueue(queueName)
    rabbitControl ! deleteQueue
    await(deleteQueue.processed)
  }

  implicit val simpleIntMarshaller = new RabbitMarshaller[Int] with RabbitUnmarshaller[Int] {
    val contentType = "text/plain"
    val encoding = "UTF-8"

    def marshall(value: Int) =
      (value.toString.getBytes, contentType, Some(encoding))

    def unmarshall(value: Array[Byte], contentType: Option[String], charset: Option[String]) = {
      new String(value).toInt
    }
  }
}
