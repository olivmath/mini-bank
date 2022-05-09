package io.olivmath.bank.app

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import io.olivmath.bank.actors.PersistentBankAccount.Command
import io.olivmath.bank.actors.Bank
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.AskPattern._

import scala.concurrent.duration._
import scala.concurrent.Future

import akka.util.Timeout
import scala.concurrent.ExecutionContext
import io.olivmath.bank.http.BankHttpApi
import akka.http.scaladsl.Http
import scala.util.Success
import scala.util.Failure

object BankApp {
  def startHTTPServer(bank: ActorRef[Command])(implicit system: ActorSystem[_]) = {
    implicit val ec: ExecutionContext = system.executionContext
    val router                        = new BankHttpApi(bank)
    val routes                        = router.routes

    // start the server
    val httpBindingFuture = Http().newServerAt("localhost", 3000).bind(routes)

    // manage the server binding
    httpBindingFuture.onComplete {
      case Success(binding) =>
        val address = {
          val addr = binding.localAddress
          s"${addr.getHostString}:${addr.getPort}"
        }
        system.log.info(s"Server online at $address")
      case Failure(ex) =>
        system.log.error(s"Failed to bind HTTP server, because: $ex")
        system.terminate()
    }
  }

  def main(args: Array[String]): Unit = {
    trait RootCommand
    case class RetrieveBankActor(replyTo: ActorRef[ActorRef[Command]]) extends RootCommand

    val rootBehavior: Behavior[RootCommand] = Behaviors.setup { context =>
      val bankActors = context.spawn(Bank(), "bank")
      Behaviors.receiveMessage {
        case RetrieveBankActor(replyTo) => {
          replyTo ! bankActors
          Behaviors.same
        }
      }
    }

    implicit val system: ActorSystem[RootCommand] = ActorSystem(rootBehavior, "BankSystem")
    implicit val timeout: Timeout                 = Timeout(5.seconds)
    implicit val ec: ExecutionContext             = system.executionContext

    val bankActorFuture: Future[ActorRef[Command]] = system.ask(replyTo => RetrieveBankActor(replyTo))
    bankActorFuture.foreach(startHTTPServer)
  }
}
