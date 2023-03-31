package part5polymorphic

import cats.effect.kernel.Concurrent
import cats.effect.{IO, IOApp, Temporal}

import scala.concurrent.duration.*
import utils.general._
import cats.syntax.apply._
object PolymorphicTemporalSuspension extends IOApp.Simple {

  // Temporal - time-blocking effects
  trait MyTemporal[F[_]] extends Concurrent[F] {
    def sleep(time: FiniteDuration): F[Unit] // semantically blocks this fiber for a specified time
  }
  // abilites: pure, map/flatMap, raiseError, uncancelable, start, ref/deferred, +sleep
  val temporalIO = Temporal[IO] // given Temporal[IO] in scope
  val chainOfEffects = IO("Loading...").myDebug *> IO.sleep(1.second) *> IO("Game ready!").myDebug
  val chainOfEffects_v2 = temporalIO.pure("Loading...").myDebug *> temporalIO.sleep(1.second) *> temporalIO.pure("Game ready!").myDebug // same

  /**
   * Exercise: generalize the following piece
   */
  def timeout[A](io: IO[A], duration: FiniteDuration): IO[A] = {
    val timeout = IO.sleep(duration)
    IO.race(io, timeout) flatMap {
      case Left(a) => IO(a)
      case Right(_) => IO.raiseError(new RuntimeException("Compilation Timeout"))
    }
  }
  import cats.syntax.flatMap._
  def timeoutGen[F[_], A](io: F[A], duration: FiniteDuration)(using temporal: Temporal[F]): F[A] = {
    val timeout = temporal.sleep(duration)
    temporal.race(io, timeout) flatMap {
      case Left(a) => temporal.pure(a)
      case Right(_) => temporal.raiseError(new RuntimeException("Compilation Timeout"))
    }
  }

  override def run: IO[Unit] = ???
}
