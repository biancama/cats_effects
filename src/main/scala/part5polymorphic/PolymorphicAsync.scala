package part5polymorphic

import cats.effect.{Async, Concurrent, IO, IOApp, Sync, Temporal}

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import utils.general.*
object PolymorphicAsync extends IOApp.Simple {
  // Async - asynchronous computations, "suspended" in F
  trait MyAsync[F[_]] extends Sync[F] with Temporal[F] {
    // fundamental description of async computations
    def executionContext: F[ExecutionContext]
    def async[A](cb: (Either[Throwable, A] => Unit) => F[Option[F[Unit]]]): F[A]
    def evalOn[A](fa: F[A], ec: ExecutionContext): F[A]

//    def async_[A](cb: (Either[Throwable, A] => Unit) => Unit): F[A] =
//      ???
//    def never[A]: F[A] = ???

    def async_[A](cb: (Either[Throwable, A] => Unit) => Unit): F[A] =
      async(kb => map(pure(cb(kb)))(_ => None))

    def never[A]: F[A] = async(_ => pure(Some(unit)))
    def never_v2[A]: F[A] =  async_(_ => ())
  }

  val asyncIO = Async[IO] // given/implicit Async[IO]

  // pure, map/flatMap, raiseError, uncancelable, start, ref/deferred, sleep, delay/defer/blocking, +
  val ec = asyncIO.executionContext
  // power: async_ + async: FFI
  val threadPool = Executors.newFixedThreadPool(10)
  type Callback[A] = Either[Throwable, A] => Unit
  val asyncMeaningOfLife: IO[Int] = IO.async_ { (cb: Callback[Int]) =>
    // start computation on some other thread pool
    threadPool.execute { () =>
      println(s"[${Thread.currentThread().getName}] Computing an async MOL")
      cb(Right(42))
    }
  }

  val asyncMeaningOfLife_v2: IO[Int] = asyncIO.async_ { (cb: Callback[Int]) =>
    // start computation on some other thread pool
    threadPool.execute { () =>
      println(s"[${Thread.currentThread().getName}] Computing an async MOL")
      cb(Right(42))
    }
  }


  val asyncMeaningOfLifeComplex: IO[Int] = IO.async { (cb: Callback[Int]) =>
    IO {
      threadPool.execute { () =>
        println(s"[${Thread.currentThread().getName}] Computing an async MOL")
        cb(Right(42))
      }
    }.as(Some(IO("Cancelled!").myDebug.void)) // <-- finalizer in case the computation gets cancelled
  }

  val asyncMeaningOfLifeComplex_v2: IO[Int] = asyncIO.async { (cb: Callback[Int]) =>
    IO {
      threadPool.execute { () =>
        println(s"[${Thread.currentThread().getName}] Computing an async MOL")
        cb(Right(42))
      }
    }.as(Some(IO("Cancelled!").myDebug.void)) // <-- finalizer in case the computation gets cancelled
  } // same

  val myExecutionContext = ExecutionContext.fromExecutorService(threadPool)
  val asyncMeaningOfLife_v3 = asyncIO.evalOn(IO(42).myDebug, myExecutionContext).guarantee(IO(threadPool.shutdown()))

  // never
  val neverIO = asyncIO.never

  /**
   * Exercises
   * 1 - implement never and async_ in terms of the big async.
   * 2 - tuple two effects with different requirements.
   */
  import cats.syntax.flatMap._
  import cats.syntax.functor._
  def firstEffect[F[_] : Concurrent, A](a: A): F[A] = Concurrent[F].pure(a)
  def secondEffect[F[_] : Sync, A](a: A): F[A] = Sync[F].pure(a)
  def tupledEffect[F[_]: Async, A](a: A): F[(A, A)] = for {
    first <- firstEffect(a)
    second <- secondEffect(a)
  } yield (first, second)

  override def run: IO[Unit] = ???
}
