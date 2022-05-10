package io.olivmath.bank.http

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout

import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.PathMatchers
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Directives.{
  respondWithDefaultHeader,
  pathEndOrSingleSlash,
  pathPrefix,
  onSuccess,
  complete,
  Segment,
  entity,
  post,
  get,
  as
}

import io.olivmath.bank.actors.PersistentBankAccount.{Response, Command}
import io.olivmath.bank.actors.PersistentBankAccount.Command._
import io.olivmath.bank.actors.PersistentBankAccount.Response._

import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._

import scala.concurrent.duration._
import scala.concurrent.Future

case class BankAccountCreateRequest(user: String, token: String, balance: Int) {
  def toCommand(replyTo: ActorRef[Response]): Command =
    CreateBankAccount(user, token, balance, replyTo)
}

case class BankAccountUpdateRequest(token: String, amount: Int) {
  def toCommand(id: String, replyTo: ActorRef[Response]): Command =
    UpdateBalance(id, token, amount, replyTo)
}

case class FailureResponse(reason: String)

/*
------------------------------------------------------------------------------------
  GET
    /bank/
      Description: get all bank accounts JSON
      Response:
        200 OK
------------------------------------------------------------------------------------
  POST
    /bank/
      Description: create bank account JSON
      Response:
        201 CREATED
        Location: /bank/uuid
------------------------------------------------------------------------------------
  GET
    /bank/uuid/
        Description: get bank account details
        Response:
            200 OK
------------------------------------------------------------------------------------
POST
  /bank/update/
      Description: update balance of bank account by uuid
      Response:
        200 Ok
------------------------------------------------------------------------------------
 */
class BankHttpApi(bank: ActorRef[Command])(implicit system: ActorSystem[_]) {
  implicit val timeout: Timeout = Timeout(5.seconds)

  def createBankAccount(request: BankAccountCreateRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(replyTo))

  def getBankAccount(id: String): Future[Response] =
    bank.ask(replyTo => GetBankAccount(id, replyTo))

  def updateBankAccount(id: String, request: BankAccountUpdateRequest) =
    bank.ask(replyTo => request.toCommand(id, replyTo))

  def httpCreateBankAccount = post {
    // parse the payload
    entity(as[BankAccountCreateRequest]) { request =>
      onSuccess(createBankAccount(request)) {
        // send HTTP response
        case BankAccountCreatedResponse(id) =>
          respondWithDefaultHeader(Location(s"/bank/$id")) {
            complete(StatusCodes.Created)
          }
      }
    }
  }

  def httpGetAllAccouts = get {
    complete(
      StatusCodes.Created
    )
  }

  def httpGetAccount(id: String) = get {
    onSuccess(getBankAccount(id)) {
      // send HTTP response
      case GetBankAccountResponse(maybeAccount) =>
        maybeAccount match {
          case Some(account) => complete(account)
          case None =>
            complete(
              StatusCodes.NotFound,
              FailureResponse(s"Account $id cannot be found")
            )
        }
    }
  }

  def httpUpdateBalance(id: String) = put {
    // parse the payload
    entity(as[BankAccountUpdateRequest]) { request =>
      onSuccess(updateBankAccount(id, request)) { case BankAccountBalanceUpdateResponse(maybeAccount) =>
        maybeAccount match {
          case Some(account) => complete(account)
          case None          => complete(FailureResponse(s"Account $id cannot be found"))
        }
      }
    }
  }

  val routes =
    pathPrefix("bank") {
      pathEndOrSingleSlash {
        httpCreateBankAccount ~ httpGetAllAccouts
      } ~
        path(Segment) { id =>
          httpGetAccount(id) ~ httpUpdateBalance(id)
        }
    }
}
