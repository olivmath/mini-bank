package io.olivmath.bank.actors

import akka.actor.typed.{ActorSystem, Scheduler, ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}

import akka.persistence.typed.scaladsl.{EventSourcedBehavior, Effect}
import akka.persistence.typed.PersistenceId

import akka.util.Timeout
import akka.NotUsed

import scala.concurrent.ExecutionContext
import java.util.UUID

object Bank {
  // message = command
  import io.olivmath.bank.actors.PersistentBankAccount.Response._
  import io.olivmath.bank.actors.PersistentBankAccount.Command
  import io.olivmath.bank.actors.PersistentBankAccount.Command.{
    GetBankAccount,
    UpdateBalance,
    CreateBankAccount
  }
  // events
  sealed trait Event
  case class BankAccountCreated(id: String) extends Event

  // state
  case class State(accounts: Map[String, ActorRef[Command]])

  // command handler
  def commandHandler(
      context: ActorContext[Command]
  ): (State, Command) => Effect[Event, State] =
    (state, command) => {
      command match {
        case createCommand @ CreateBankAccount(_, _, _, _) => {
          val id = UUID.randomUUID().toString()
          val newBankAccount = context.spawn(PersistentBankAccount(id), id)
          Effect
            .persist(BankAccountCreated(id))
            .thenReply(newBankAccount)(_ => createCommand)
        }
        case updateCommand @ UpdateBalance(id, token, amount, replyTo) => {
          state.accounts.get(id) match {
            case Some(account) => {
              Effect.reply(account)(updateCommand)
            }
            case None => {
              Effect.reply(replyTo)(BankAccountBalanceUpdateResponse(None))
            }
          }
        }
        case getCommand @ GetBankAccount(id, replyTo) => {
          state.accounts.get(id) match {
            case Some(account) =>
              Effect.reply(account)(getCommand)
            case None =>
              Effect.reply(replyTo)(GetBankAccountResponse(None))
          }
        }
      }
    }
  // event handler
  def eventHandler(context: ActorContext[Command]): (State, Event) => State =
    (state, event) => {
      event match {
        case BankAccountCreated(id) => {
          val account = context
            .child(id)
            .getOrElse(context.spawn(PersistentBankAccount(id), id))
            .asInstanceOf[ActorRef[Command]]
          state.copy(state.accounts + (id -> account))
        }
      }
    }
  // behavior
  def apply(): Behavior[Command] = {
    Behaviors.setup(context => {
      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId("bank"),
        emptyState = State(Map()),
        commandHandler = commandHandler(context),
        eventHandler = eventHandler(context)
      )
    })
  }
}

object BankPlayground {
  import PersistentBankAccount.Response._
  import PersistentBankAccount.Response

  import PersistentBankAccount.Command._

  def main(args: Array[String]): Unit = {
    val rootBehavior: Behavior[NotUsed] = Behaviors.setup { context =>
      val bank = context.spawn(Bank(), "bank")
      val logger = context.log

      val responseHandler = context.spawn(
        Behaviors.receiveMessage[Response] {
          case BankAccountCreatedResponse(id) =>
            logger.warn(
              s"\033[92m\n\nSuccessfully created bank account $id\n\n\033[0m"
            )
            Behaviors.same
          case GetBankAccountResponse(maybeBankAccount) =>
            logger.warn(
              s"\033[92m\n\nAccount details: $maybeBankAccount\n\n\033[0m"
            )
            Behaviors.same
          case BankAccountBalanceUpdateResponse(maybeBackAccount) =>
            logger.warn(
              s"\033[92m\n\nSuccessfully update balance $maybeBackAccount\n\n\033[0m"
            )
            Behaviors.same
        },
        "replyHandler"
      )

      // bank ! CreateBankAccount("Lucas Oliveira", "BTC", 10, responseHandler)
      // bank ! GetBankAccount("29fe4537-5eda-4b9e-98f5-e7d985435dee", responseHandler)
      // bank ! UpdateBalance("29fe4537-5eda-4b9e-98f5-e7d985435dee", "BTC", 100, responseHandler)

      Behaviors.empty
    }

    val system = ActorSystem(rootBehavior, "BankDemo")
  }
}
