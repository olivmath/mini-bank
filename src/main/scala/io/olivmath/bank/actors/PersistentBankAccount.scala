package io.olivmath.bank.actors

import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.PersistenceId
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef

// a single bank account
object PersistentBankAccount {
  // commands = message
  sealed trait Command
  object Command {
    case class CreateBankAccount(
      user: String,
      token: String,
      balance: Int,
      replyTo: ActorRef[Response]
    ) extends Command
    case class UpdateBalance(
      id: String,
      token: String,
      amount: Int,
      replyTo: ActorRef[Response]
    ) extends Command
    case class GetBankAccount(
      id: String,
      replyTo: ActorRef[Response]
    ) extends Command
  }
  import Command._
  // event = to persistir in DB
  sealed trait Event
  case class BankAccountCreated(bankAccount: BankAccount) extends Event
  case class BalanceUpdated(amount: Int)                  extends Event

  // state
  case class BankAccount(
    id: String,
    user: String,
    token: String,
    balance: Int
  )
  // response
  sealed trait Response
  object Response {
    case class BankAccountCreatedResponse(id: String) extends Response
    case class BankAccountBalanceUpdateResponse(
      maybeBackAccount: Option[BankAccount]
    ) extends Response
    case class GetBankAccountResponse(
      maybeBackAccount: Option[BankAccount]
    ) extends Response
  }
  import Response._

  // command handler = message handler => persist an event
  // event hadler => update state
  // state
  val commandHandler: (BankAccount, Command) => Effect[Event, BankAccount] =
    (state, command) => {
      command match {
        case GetBankAccount(_, replyTo) =>
          Effect.reply(replyTo)(GetBankAccountResponse(Some(state)))
        case CreateBankAccount(user, token, balance, replyTo) => {
          val id = state.id
          Effect
            .persist(BankAccountCreated(BankAccount(id, user, token, balance)))
            .thenReply(replyTo)(_ => BankAccountCreatedResponse(id))
        }
        case UpdateBalance(_, _, amount, replyTo) => {
          val newBalance = amount + state.balance
          if (newBalance < 0) {
            Effect.reply(replyTo)(BankAccountBalanceUpdateResponse(None))
          } else {
            Effect
              .persist(BalanceUpdated(amount))
              .thenReply(replyTo)(newState => BankAccountBalanceUpdateResponse(Some(newState)))
          }
        }
      }
    }
  val eventHandler: (BankAccount, Event) => BankAccount = (state, command) => {
    command match {
      case BalanceUpdated(amount) =>
        state.copy(balance = state.balance + amount)
      case BankAccountCreated(bankAccount) => bankAccount
    }
  }

  def apply(id: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, BankAccount](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = BankAccount(id, "", "", 0),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}
