package part3concurrency

import cats.effect.kernel.Outcome.{Canceled, Errored, Succeeded}
import cats.effect.{Fiber, IO, IOApp, Outcome}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.*
import scala.util.Left
object RacingIOs extends IOApp.Simple {
  import utils._
  def runWithSleep[A](value: A, duration: FiniteDuration): IO[A] =
    (
      IO(s"starting computation $value").myDebug >>
      IO.sleep(duration).myDebug >>
      IO(s"computation for $value done").myDebug >>
      IO(value)
    ).onCancel(IO(s"computation cancelled for $value").myDebug.void)

  def testRace() = {
    val meaningOfLife = runWithSleep(42, 1.second)
    val favLanguage = runWithSleep("Scala", 2.second)
    val first:IO[Either[Int, String]] = IO.race(meaningOfLife, favLanguage)
    first.flatMap {
      case Left(mon) => IO(s"meaning of life won: $mon")
      case Right(lang) => IO(s"lang won: $lang")
    }
  }

  /*
        - both IOs run on separate fibers
        - the first one to finish will complete the result
        - the loser will be canceled
       */
  def testRacePair() = {
    val meaningOfLife = runWithSleep(42, 1.second)
    val favLanguage = runWithSleep("Scala", 2.second)
    val raceResult: IO[Either[
      (Outcome[IO, Throwable, Int], Fiber[IO, Throwable, String]) ,
      (Fiber[IO, Throwable, Int], Outcome[IO, Throwable, String])
    ]] = IO.racePair(meaningOfLife, favLanguage)

    raceResult.flatMap{
      case Left((outMol, fibLang)) => fibLang.cancel >> IO("MOL won").myDebug >> IO(outMol)
      case Right((fibMol, outLang)) => fibMol.cancel >> IO("LANG won").myDebug >> IO(outLang)
    }

  }

  /**
   * Exercises:
   * 1 - implement a timeout pattern with race
   * 2 - a method to return a LOSING effect from a race (hint: use racePair)
   * 3 - implement race in terms of racePair
   */
  // 1
  def timeout[A](io: IO[A], duration: FiniteDuration): IO[A] = {
    val timeout = IO.sleep(duration)
    IO.race(io, timeout) flatMap {
      case Left(a) => IO(a)
      case Right(_) => IO.raiseError(new RuntimeException("Compilation Timeout"))
    }
  }

  val importantTask = IO.sleep(2.seconds) >> IO(42).myDebug
  val testTimeout = timeout(importantTask, 1.seconds)
  val testTimeout_v2 = importantTask.timeout(1.seconds)

  // 2
  def unrace[A, B](ioa: IO[A], iob: IO[B]): IO[Either[A, B]] =
    IO.racePair(ioa, iob) flatMap {
      case Left((_, fibB)) => fibB.join flatMap {
        case Succeeded(effectB) => effectB.map(result => Right(result))
        case Canceled() => IO.raiseError(new RuntimeException("Loser cancelled"))
        case Errored(e) => IO.raiseError(e)
      }
      case Right((fibA, _)) => fibA.join flatMap {
        case Succeeded(effectA) => effectA.map(result => Left(result))
        case Canceled() => IO.raiseError(new RuntimeException("Loser cancelled"))
        case Errored(e) => IO.raiseError(e)
      }
    }
  val importantTask_v1 = IO.sleep(2.seconds) >> IO(42)
  val importantTask_v2 = IO.sleep(3.seconds) >> IO(54)

  val testUnrace =  unrace(importantTask_v1, importantTask_v2)

  // 3
  def simpleRace[A, B](ioa: IO[A], iob: IO[B]): IO[Either[A, B]] =
    IO.racePair(ioa, iob) flatMap {
      case Left((outA, fibB)) => outA match {
        case Succeeded(effectA) => fibB.cancel >> effectA.map(result => Left(result))
        case Errored(e) => fibB.cancel >> IO.raiseError(e)
        case Canceled() => fibB.join.flatMap {
          case Succeeded(effectB) => effectB.map(result => Right(result))
          case Errored(e) => IO.raiseError(e)
          case Canceled() => IO.raiseError(new RuntimeException("Both are cancelled"))
        }
      }
      case Right((fibA, outB)) => outB match {
        case Succeeded(effectB) => fibA.cancel >> effectB.map(result => Right(result))
        case Errored(e) => fibA.cancel >> IO.raiseError(e)
        case Canceled() => fibA.join.flatMap {
          case Succeeded(effectA) => effectA.map(result => Left(result))
          case Errored(e) => IO.raiseError(e)
          case Canceled() => IO.raiseError(new RuntimeException("Both are cancelled"))
        }
      }
    }


  //override def run: IO[Unit] = testRace().myDebug .void
  //override def run: IO[Unit] = testRacePair().myDebug.void
  //override def run: IO[Unit] = testTimeout.void
  override def run: IO[Unit] = testUnrace.myDebug.void
}
