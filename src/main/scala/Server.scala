import akka.actor.{Actor, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.{AskTimeoutException, pipe}
import akka.util.{ByteString, Timeout}
import com.typesafe.config.ConfigFactory

import scala.jdk.DurationConverters._
import scala.util.{Failure, Success}

object Server extends App {

  implicit val system: ActorSystem = ActorSystem("RountingAPI")
  implicit val executionContext = system.dispatcher

  val config = ConfigFactory.load()
  val loadBalancer: LoadBalancer = RoundRobinLoadBalancer(config)
//  val loadBalancer: LoadBalancer = WeightedRoundRobinLoadBalancer(config)

  implicit val timeout: Timeout =
    Timeout(config.getDuration("timeout").toScala)

  val slowThresholdMs = config.getDuration("slow-threhold").toMillis

  class InstanceRouter() extends Actor {
    def receive: Receive = { case request: HttpRequest =>
      Http()
        .singleRequest(request)
        .pipeTo(sender())
    }
  }

  val route: Route = path("api" / "endpoint") {
    post {
      entity(as[ByteString]) { requestData =>
        println("\nReceived Request: ")
        val resource = loadBalancer.getNextInstance()

        resource match {
          case Right(resource) => {
            println(s"Resource: ${resource.getAddress()}")

            val forwardRequest = HttpRequest(
              uri = s"${resource.getAddress()}/api/endpoint",
              method = akka.http.scaladsl.model.HttpMethods.POST,
              entity = requestData
            )

//            val requestJsonString = requestData.utf8String
//            println(
//              s"Received Request: $requestJsonString, forwarding to ${forwardRequest.uri}"
//            )

            println(
              s"Forwarding to ${forwardRequest.uri}"
            )

            val instanceRouter =
              system.actorOf(Props(new InstanceRouter()))

            val startTimeMs = System.currentTimeMillis()
            val forwardResponse =
              akka.pattern
                .ask(instanceRouter, forwardRequest)
                .mapTo[HttpResponse]

            onComplete(forwardResponse)({
              case Success(response) =>
                val elapsedTimeMs = System.currentTimeMillis() - startTimeMs

                if (elapsedTimeMs > slowThresholdMs) {
                  loadBalancer.onSlowSuccess(resource)
                } else {
                  loadBalancer.onSuccess(resource)
                }
                println(
                  s"Finished, forwarding to ${forwardRequest.uri} with response: ${response.status.value}"
                )
                complete(response)
              case Failure(_: AskTimeoutException) =>
                loadBalancer.onFailure(resource)
                println(
                  s"Finished, forwarding to ${forwardRequest.uri} with error: Downstream service timed out"
                )
                complete(
                  StatusCodes.InternalServerError,
                  "Error forwarding request: Downstream service timed out"
                )
              //todo add onSlow
              case Failure(ex) =>
                loadBalancer.onFailure(resource)
                println(
                  s"Finished, forwarding to ${forwardRequest.uri} with error ${ex.getMessage}"
                )
                complete(
                  StatusCodes.InternalServerError,
                  s"Error forwarding request: ${ex.getMessage}"
                )
            })
          }
          case _ => {
            println(s"Resource: No instances available")
            println(
              s"Failed to forwarding request to downstream service: No instances available"
            )
            complete(
              StatusCodes.InternalServerError,
              "Error forwarding request: No instances available"
            )
          }
        }
      }
    }
  }

  val bindingFuture =
    Http().newServerAt("0.0.0.0", 8081).bind(route)

  bindingFuture.foreach { binding =>
    {
      val address =
        s"http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}/"
      println(s"Server running at $address")
    }
  }
}
