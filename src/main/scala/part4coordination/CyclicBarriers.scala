package part4coordination

import cats.effect.std.CyclicBarrier
import cats.effect.{Deferred, IO, IOApp, Ref}

import scala.util.Random
import utils.*

import scala.concurrent.duration.*
import cats.syntax.parallel.*
import part4coordination.CountdownLatches.CDLatch.State
object CyclicBarriers extends IOApp.Simple {
  /*
      A cyclic barrier is a coordination primitive that
      - is initialized with a count
      - has a single API: await
      A cyclic barrier will (semantically) block all fibers calling its await() method until we have exactly N fibers waiting,
        at which point the barrier will unblock all fibers and reset to its original state.
      Any further fiber will again block until we have exactly N fibers waiting.
      ...
      And so on.
     */
  // example: signing up for a social network just about to be launched
  def createUser(id: Int, barrier: CyclicBarrier[IO]): IO[Unit] = for {
    _ <- IO.sleep((Random.nextDouble * 500).toInt.millis)
    _ <- IO(s"[user $id] Just heard there's a new social network - signing up for the waitlist...").myDebug
    _ <- IO.sleep((Random.nextDouble * 1500).toInt.millis)
    _ <- IO(s"[user $id] On the waitlist now, can't wait!").myDebug
    _ <- barrier.await // block the fiber when there are exactly N users waiting
    _ <- IO(s"[user $id] OMG this is so cool!").myDebug
  } yield ()

  def openNetwork(): IO[Unit] = for {
    _ <- IO("[announcer] The Rock the JVM social network is up for registration! Launching when we have 10 users!").myDebug
    barrier <- CyclicBarrier[IO](10)
    _ <- (1 to 20).toList.parTraverse(id => createUser(id, barrier))
  } yield ()
  override def run: IO[Unit] = openNetwork()
}

abstract class CBarrier {
  def await: IO[Unit]

}

object CBarrier {
  case class State(nWaiting: Int, signal: Deferred[IO, Unit])
  def apply(capacity: Int ): IO[CBarrier] = for {
    signal <- Deferred[IO, Unit]
    state <- Ref[IO].of[State](State(capacity, signal))
  } yield new CBarrier {
      override def await: IO[Unit] = Deferred[IO, Unit].flatMap {newSignal =>
        state.modify {
          case State(1, s) => State(capacity, newSignal) -> signal.complete(()).void
          case State(n, s) => State(n - 1, s) -> signal.get
        }.flatten
      }
  }
}
