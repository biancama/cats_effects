package part4coordination

import cats.effect.kernel.Outcome.{Canceled, Errored, Succeeded}
import cats.effect.{Concurrent, Deferred, IO, IOApp, Ref}

import scala.util.Random
import scala.concurrent.duration.*
import utils.*
import cats.syntax.parallel.*

import scala.collection.immutable.Queue

abstract class Mutex {
  def acquire: IO[Unit]
  def release: IO[Unit]
}

object Mutex {
  type Signal = Deferred[IO, Unit]

  def createSignal(): IO[Signal] = Deferred[IO, Unit]

  case class State(locked: Boolean, waiting: Queue[Signal])

  val unlocked = State(false, Queue())

  def createSimpleMutex(state: Ref[IO, State]): Mutex = {
    new Mutex {
      /*
            Change the state of the Ref:
            - if the mutex is currently unlocked, state becomes (true, [])
            - if the mutex is locked, state becomes (true, queue + new signal) AND WAIT ON THAT SIGNAL.
           */
      override def acquire: IO[Unit] = createSignal().flatMap { signal =>
        state.modify { // IO[IO[Unit]]
          case State(false, _) => State(true, Queue()) -> IO.unit
          case State(true, q) => State(true, q.enqueue(signal)) -> signal.get
        }.flatten // modify returns IO[B], our B is IO[Unit], so modify returns IO[IO[Unit]], we need to flatten
      }

      /*
            Change the state of the Ref:
            - if the mutex is unlocked, leave the state unchanged
            - if the mutex is locked,
              - if the queue is empty, unlock the mutex, i.e. state becomes (false, [])
              - if the queue is not empty, take a signal out of the queue and complete it (thereby unblocking a fiber waiting on it)
           */
      override def release: IO[Unit] = state.modify {
        case State(false, _) => unlocked -> IO.unit
        case State(true, Queue()) => unlocked -> IO.unit
        case State(true, q) =>
          val (sig, newQueue) = q.dequeue
          State(true, newQueue) -> sig.complete(()).void
      }.flatten
    }
  }

  def createMutexWithCancellation(state: Ref[IO, State]): Mutex = new Mutex {

    override def acquire: IO[Unit] =
      IO.uncancelable { poll =>
        createSignal().flatMap { signal =>

          val cleanup = state.modify {
            case State(locked, queue) =>
              val newQueue = queue.filterNot(_ eq signal)
              println(s"State after cancelling: $locked, $newQueue")
              State(locked, newQueue) -> release
          }.flatten


          state.modify { // IO[IO[Unit]]
            case State(false, _) => State(true, Queue()) -> IO.unit
            case State(true, q) =>
              State(true, q.enqueue(signal)) -> poll(signal.get).onCancel(cleanup)

          }.flatten // modify returns IO[B], our B is IO[Unit], so modify returns IO[IO[Unit]], we need to flatten
        }
      }

    override def release: IO[Unit] =

        state.modify {
          case State(false, _) => unlocked -> IO.unit
          case State(true, Queue()) => unlocked -> IO.unit
          case State(true, q) =>
            val (sig, newQueue) = q.dequeue
            State(true, newQueue) -> sig.complete(()).void
        }.flatten

  }
  def create: IO[Mutex] = Ref[IO].of(unlocked).map { state =>
    //createSimpleMutex(state)
    createMutexWithCancellation(state)
  }
}

// generic mutex after the polymorphic concurrent exercise
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.effect.syntax.monadCancel._
abstract class MutexV2[F[_]] {
  def acquire: F[Unit]
  def release: F[Unit]
}

object MutexV2 {
  type Signal[F[_]] = Deferred[F, Unit]
  case class State[F[_]](locked: Boolean, waiting: Queue[Signal[F]])

  def unlocked[F[_]] = State[F](locked = false, Queue())
  def createSignal[F[_]](using concurrent: Concurrent[F]): F[Signal[F]] = concurrent.deferred[Unit]

  def create[F[_]](using concurrent: Concurrent[F]): F[MutexV2[F]] =
    concurrent.ref(unlocked).map(initialState => createMutexWithCancellation(initialState))

  def createMutexWithCancellation[F[_]](state: Ref[F, State[F]])(using concurrent: Concurrent[F]): MutexV2[F] =
    new MutexV2[F] {
      override def acquire = concurrent.uncancelable { poll =>
        createSignal.flatMap { signal =>

          val cleanup = state.modify {
            case State(locked, queue) =>
              val newQueue = queue.filterNot(_ eq signal)
              State(locked, newQueue) -> release
          }.flatten

          state.modify {
            case State(false, _) => State[F](locked = true, Queue()) -> concurrent.unit
            case State(true, queue) => State[F](locked = true, queue.enqueue(signal)) -> poll(signal.get).onCancel(cleanup)
          }.flatten
        }
      }

      override def release = state.modify {
        case State(false, _) => unlocked[F] -> concurrent.unit
        case State(true, queue) =>
          if (queue.isEmpty) unlocked[F] -> concurrent.unit
          else {
            val (signal, rest) = queue.dequeue
            State[F](locked = true, rest) -> signal.complete(()).void
          }
      }.flatten
    }

}

object MutexPlayground extends IOApp.Simple {

  def criticalTask() : IO[Int] = IO.sleep(1.second) >> IO(Random.nextInt(100))
  def createNonLockingTask(id: Int): IO[Int] = for {
    _ <-IO(s"[task $id] working ...").myDebug
    res <- criticalTask()
    _ <- IO(s"[task $id] got result: $res").myDebug
  } yield res

  def demoNonLockingTasks(): IO[List[Int]] = (1 to 10).toList.parTraverse(id => createNonLockingTask(id)) // all tasks start at the same time

  def createLockingTask(id: Int, mutex: Mutex): IO[Int] = for {
    _ <- IO(s"[task $id] waiting for permission...").myDebug
    _ <- mutex.acquire // blocks if the mutex has been acquired by some other fiber
    // critical section
    _ <- IO(s"[task $id] working...").myDebug
    res <- criticalTask()
    _ <- IO(s"[task $id] got result: $res").myDebug
    // critical section end
    _ <- mutex.release
    _ <- IO(s"[task $id] lock removed.").myDebug
  } yield res

  def demoLockingTasks() = for {
    mutex <- Mutex.create
    results <- (1 to 10).toList.parTraverse(id => createLockingTask(id, mutex))
  } yield results
  // only one task will proceed at one time

  //override def run: IO[Unit] = demoNonLockingTasks().myDebug.void

  def createCancellingTask(id: Int, mutex: Mutex):IO[Int] = {
    if (id % 2 == 0) createLockingTask(id, mutex)
    else for {
      fib <- createLockingTask(id, mutex).onCancel(IO(s"[task $id] received cancellation").myDebug.void).start
      _ <- IO.sleep(2.seconds) >> fib.cancel
      out <- fib.join
      result <- out match {
        case Succeeded(effect) => effect
        case Errored(_) => IO(-1)
        case Canceled() => IO(-2)
      }
    } yield result
  }

  def demoCancellingTasks() = for {
    mutex <- Mutex.create
    results <- (1 to 10).toList.parTraverse(id => createCancellingTask(id, mutex))
  } yield results
 // override def run: IO[Unit] = demoLockingTasks().myDebug.void

  override def run: IO[Unit] = demoCancellingTasks().myDebug.void


}