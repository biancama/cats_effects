package udemy.part2ErrorHandling


import cats._
import cats.data.Validated.{Invalid, Valid}
import cats.data.{EitherT, Validated, ValidatedNec}
import cats.implicits._
import cats.effect._
import cats.effect.implicits._

import scala.util.control.NonFatal

object Controller {
  case class Request(fromAccount: String, toAccount: String, amount: String)
  case class Response(status: Int, body: String)
  import Validations._
  // Validate the from and to account number and the amount
  // If validation fails return with code 400 and some error message
  // otherwise call transfer method
  // if there any domain error return with code 400 and some error message
  // if there is any other error, return a Response with code 500 and Internal Server Error Message
  // Otherwise, return a Response with code 200 and Transfer successfully executed


  def postTransfer(request: Request): IO[Response] = {
    val response = (validateAccountNumber(request.fromAccount),
      validateAccountNumber(request.toAccount),
      validateDouble(request.amount)).tupled match {
      case Valid((fromAccountNumber, toAccountNumber, amount)) =>
        Service.transfer(fromAccountNumber, toAccountNumber, amount).map {
          case Right(()) =>
            Response(200, "Transfer successfully executed")
          case Left(error) =>
            Response(400, error.toString)
        }
      case Invalid(errors) =>
        Response(400, errors.mkString_(", ")).pure[IO]
    }
    response.recoverWith {
      case NonFatal(e) => Response(500, "Internal Server Error Message").pure[IO]
    }
  }
}

object ErrorHandlingApp extends IOApp {
  import Controller._
  import Repository._
  import Models._

  override def run(args: List[String]): IO[ExitCode] = {
    val request = Request("12345", "56789", "2000")
    val savedAccounts = for {
      _ <- saveAccount(Account("12345", 10000))
      _ <- saveAccount(Account("56789", 2000))
    } yield()

    savedAccounts >> postTransfer(request)
      .flatTap(resp => IO.println(s"$resp with final $data"))
      .as(ExitCode.Success)
  }
}

object Validations {
  type Valid[A] = ValidatedNec[String, A]
  def validateDouble(s: String): Valid[Double] = Validated.fromOption(s.toDoubleOption, s"$s is not a valid double").toValidatedNec
  def validateAccountNumber(s: String): Valid[String] = Validated.condNec(
    s.matches("^[a-zA-Z0-9]*$"),
    s,
    s"the account $s is NOT valid"
  )
}

trait DomainError
case class InsufficientBalanceError(actualBalance: Double, amountToWithdraw: Double) extends DomainError
case class MaximumBalanceExceededError(actualBalance: Double, amountToDeposit: Double) extends DomainError
case class AccountNotFound(accountNumber: String) extends DomainError
object Models {
  import Validations._
  val MAX_AMOUNT = 100000.0
  case class Account(number: String, balance: Double) {
    def withdraw(amount: Double): Either[DomainError, Account] =
      if (amount <= balance) then Right(this.copy(balance = balance - amount))
      else Left(InsufficientBalanceError(balance, amount))

    def deposit(amount: Double): Either[DomainError, Account] =
      if (amount + balance <= MAX_AMOUNT) then Right(this.copy(balance = balance + amount))
      else Left(MaximumBalanceExceededError(balance, amount))

  }
}

object Repository {
  import udemy.part2ErrorHandling.Models.Account
  import cats.effect.syntax._

    var data = Map.empty[String, Account]
    def findAccountByNumber(number: String): IO[Option[Account]] = IO.pure(data.get(number))
    def findAccountByNumber1(number: String): Option[Account] = data.get(number)

    def saveAccount(account: Account): IO[Unit] = IO {
      data = data + (account.number -> account)
    }
}

object Service {
  import Models._
  def transfer(fromAccountNumber: String, toAccountNumber: String, amount: Double): IO[Either[DomainError, Unit]] = {
    import cats.syntax.either._
    import cats.data.OptionT
    import cats.data.EitherT
    Repository.findAccountByNumber(fromAccountNumber).flatMap { fromAccountOpt =>
      Repository.findAccountByNumber(toAccountNumber).flatMap { toAccountOpt =>
        val accounts: Either[DomainError, (Account, Account)] = for {
          fromAccount <- fromAccountOpt.toRight(AccountNotFound(fromAccountNumber))
          toAccount <- toAccountOpt.toRight(AccountNotFound(toAccountNumber))
          updatedFormAccount <- fromAccount.withdraw(amount)
          updatedToAccount <- toAccount.deposit(amount)
        } yield (updatedFormAccount, updatedToAccount)
        accounts.traverse { case (from, to) =>
          Repository.saveAccount(from) >> Repository.saveAccount(to)
        }
      }
    }
    // with monad transformers

    val accounts: EitherT[IO, DomainError, (Account, Account)] = for {
      fromAccount <- EitherT.fromOption[IO](Repository.findAccountByNumber1(fromAccountNumber), AccountNotFound(fromAccountNumber))
      toAccount <- EitherT.fromOption[IO](Repository.findAccountByNumber1(toAccountNumber), AccountNotFound(fromAccountNumber))
      updatedFormAccount <- EitherT.fromEither(fromAccount.withdraw(amount))
      updatedToAccount <- EitherT.fromEither(toAccount.deposit(amount))
    } yield (updatedFormAccount, updatedToAccount)
    accounts.value.flatMap( e => e.traverse {
      case (from, to) =>
        Repository.saveAccount(from) >> Repository.saveAccount(to)
    })
    //IO.raiseError(new Exception("boom"))
    //IO.raiseError(new OutOfMemoryError("boom"))
  }
}