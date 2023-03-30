package part5polymorphic

import cats.effect.kernel.Outcome
import cats.effect.{Concurrent, Deferred, Fiber, IO, IOApp, MonadCancel, Ref, Spawn}

object PolymorphicCoordination extends IOApp.Simple {

  // Concurrent - Ref + Deferred for ANY effect type
  trait MyConcurrent[F[_]] extends Spawn[F] {
    def ref[A](a: A): F[Ref[F, A]]
    def deferred[A]: F[Deferred[F, A]]
  }

  val concurrentIO = Concurrent[IO] // given instance of Concurrent[IO]

  val aDeferred = Deferred[IO, Int] // given/implicit Concurrent[IO] in scope
  val aDeferred_v2 = concurrentIO.deferred[Int]
  val aRef = concurrentIO.ref(42)
  // capabilities: pure, map/flatMap, raiseError, uncancelable, start (fibers), + ref/deferred

  import utils.general._

  import scala.concurrent.duration._

  def eggBoiler(): IO[Unit] = {
    def eggReadyNotification(signal: Deferred[IO, Unit]) = for {
      _ <- IO("Egg boiling on some other fiber, waiting...").myDebug
      _ <- signal.get
      _ <- IO("EGG READY!").myDebug
    } yield ()

    def tickingClock(counter: Ref[IO, Int], signal: Deferred[IO, Unit]): IO[Unit] = for {
      _ <- IO.sleep(1.second)
      count <- counter.updateAndGet(_ + 1)
      _ <- IO(count).myDebug
      _ <- if (count >= 10) signal.complete(()) else tickingClock(counter, signal)
    } yield ()

    for {
      counter <- Ref[IO].of(0)
      signal <- Deferred[IO, Unit]
      notificationFib <- eggReadyNotification(signal).start
      clock <- tickingClock(counter, signal).start
      _ <- notificationFib.join
      _ <- clock.join
    } yield ()
  }

  import cats.syntax.flatMap._ // flatMap
  import cats.syntax.functor._ // map
  import cats.effect.syntax.spawn._ // start extension method

//  // added here explicitly due to a Scala 3 bug that we discovered during lesson recording
//  def unsafeSleepDupe[F[_], E](duration: FiniteDuration)(using mc: MonadCancel[F, E]): F[Unit] =
//    mc.pure(Thread.sleep(duration.toMillis))

  def polymorphicEggBoiler[F[_]](using concurrent: Concurrent[F]): F[Unit] = {
    def eggReadyNotification(signal: Deferred[F, Unit]) = for {
      _ <- concurrent.pure("Egg boiling on some other fiber, waiting...").myDebug
      _ <- signal.get
      _ <- concurrent.pure("EGG READY!").myDebug
    } yield ()

    def tickingClock(counter: Ref[F, Int], signal: Deferred[F, Unit]): F[Unit] = for {
      _ <- unsafeSleep[F, Throwable](1.second)
      count <- counter.updateAndGet(_ + 1)
      _ <- concurrent.pure(count).myDebug
      _ <- if (count >= 10) signal.complete(()).void else tickingClock(counter, signal)
    } yield ()

    for {
      counter <- concurrent.ref(0)
      signal <- concurrent.deferred[Unit]
      notificationFib <- eggReadyNotification(signal).start
      clock <- tickingClock(counter, signal).start
      _ <- notificationFib.join
      _ <- clock.join
    } yield ()
  }

  /**
   * Exercises:
   * 1. Generalize racePair
   * 2. Generalize the Mutex concurrency primitive for any F
   */

  type RaceResult[A, B] = Either[
    (Outcome[IO, Throwable, A], Fiber[IO, Throwable, B]), // (winner result, loser fiber)
    (Fiber[IO, Throwable, A], Outcome[IO, Throwable, B]) // (loser fiber, winner result)
  ]
  type DeferredResult[A, B] = Either[Outcome[IO, Throwable, A], Outcome[IO, Throwable, B]]

  def ourRacePair[A, B](ioa: IO[A], iob: IO[B]): IO[RaceResult[A, B]] = IO.uncancelable { poll =>
    for {
      signal <- Deferred[IO, DeferredResult[A, B]]
      fibA <- ioa.guaranteeCase(outcomeA => signal.complete(Left(outcomeA)).void).start
      fibB <- iob.guaranteeCase(outcomeB => signal.complete(Right(outcomeB)).void).start
      result: DeferredResult[A, B] <- poll(signal.get) onCancel { // blocking call - should be cancelable
        for {
          cancelFibA <- fibA.cancel.start // start another fiber to cancel at the same time
          cancelFibB <- fibB.cancel.start
          _ <- cancelFibA.join
          _ <- cancelFibB.join
        } yield ()
      }
    } yield result match {
      case Left(outcomeA) => Left(outcomeA, fibB)
      case Right(outcomeB) => Right(fibA, outcomeB)
    }
  }

  import cats.effect.syntax.monadCancel.* // guaranteeCase extension method
  import cats.effect.syntax.spawn.* // start extension method

  type RaceResultGen[F[_], A, B] = Either[
    (Outcome[F, Throwable, A], Fiber[F, Throwable, B]), // (winner result, loser fiber)
    (Fiber[F, Throwable, A], Outcome[F, Throwable, B]) // (loser fiber, winner result)
  ]
  type DeferredResultGen[F[_], A, B] = Either[Outcome[F, Throwable, A], Outcome[F, Throwable, B]]

  def ourRacePairGen[F[_], A, B](ioa: F[A], iob: F[B])(using concurrent: Concurrent[F]): F[RaceResultGen[F, A, B]] = concurrent.uncancelable { poll =>
    for {
      signal <- concurrent.deferred[DeferredResultGen[F, A, B]]
      fibA <- ioa.guaranteeCase(outcomeA => signal.complete(Left(outcomeA)).void).start
      fibB <- iob.guaranteeCase(outcomeB => signal.complete(Right(outcomeB)).void).start
      result: DeferredResultGen[F, A, B] <- poll(signal.get) onCancel { // blocking call - should be cancelable
        for {
          cancelFibA <- fibA.cancel.start // start another fiber to cancel at the same time
          cancelFibB <- fibB.cancel.start
          _ <- cancelFibA.join
          _ <- cancelFibB.join
        } yield ()
      }
    } yield result match {
      case Left(outcomeA) => Left((outcomeA, fibB))
      case Right(outcomeB) => Right((fibA, outcomeB))
    }
  }
  //override def run: IO[Unit] = eggBoiler()
  override def run: IO[Unit] = polymorphicEggBoiler[IO]
}
