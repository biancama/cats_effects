package part3concurrency

import cats.effect.IO.{IOCont, Uncancelable}
import cats.effect.kernel.Outcome.{Canceled, Errored, Succeeded}
import cats.effect.{Fiber, IO, IOApp, Outcome}
import cats.effect.unsafe.implicits.global

import scala.concurrent.duration.*
object Fibers extends IOApp.Simple {

  val meaningOfLife = IO.pure(42)
  val favlang = IO.pure("scala")
  import utils._
  def sameThreadIos() = for {
    _ <- meaningOfLife.myDebug
    _ <- favlang.myDebug
  } yield ()

  // introduce the Fiber
  def createFiber: Fiber[IO, Throwable, String] = ???  // almost impossible to create fibers manually
  // the fiber is not actually started, but the fiber allocation is wrapped in another effect
  val aFiber: IO[Fiber[IO, Throwable, Int]] = meaningOfLife.myDebug.start

  def differentThreadIos() = for {
    _ <- aFiber
    _ <- favlang.myDebug
  } yield ()

  // joining a fiber
  def runOnSomeOtherThread[A](io: IO[A]): IO[Outcome[IO, Throwable, A]] = for {
    fib <- io.start
    result <- fib.join // an effect which waits for the fiber to terminate
  } yield result
  /*
    possible outcomes:
    - success with an IO
    - failure with an exception
    - cancelled
   */

  val someIOOnAnotherThread = runOnSomeOtherThread(meaningOfLife)
  val someResultFromAnotherThread = someIOOnAnotherThread.flatMap {
    case Succeeded(effect) => effect
    case Errored(e) => IO(0)
    case Canceled() => IO(0)
  }

  def throwOnAnotherThread() = for {
    fib <- IO.raiseError[Int](new RuntimeException("no number for you")).start
    result <- fib.join
  } yield result

  def testCancel() = {
    val task = IO("starting").myDebug >> IO.sleep(1.second) >> IO("done").myDebug
    // onCancel is a "finalizer", allowing you to free up resources in case you get canceled
    val taskWithCancellationHandler = task.onCancel(IO("I'm being cancelled!").myDebug.void)
    for {
      //fib <- task.start // on a separate Thread
      fib <- taskWithCancellationHandler.start
      _ <- IO.sleep(500.millis) >> IO("cancelling").myDebug
      _ <- fib.cancel
      result <- fib.join
    } yield result
  }

  /**
   * Exercises:
   *  1. Write a function that runs an IO on another thread, and, depending on the result of the fiber
   *    - return the result in an IO
   *    - if errored or cancelled, return a failed IO
   *
   * 2. Write a function that takes two IOs, runs them on different fibers and returns an IO with a tuple containing both results.
   *    - if both IOs complete successfully, tuple their results
   *    - if the first IO returns an error, raise that error (ignoring the second IO's result/error)
   *    - if the first IO doesn't error but second IO returns an error, raise that error
   *    - if one (or both) canceled, raise a RuntimeException
   *
   * 3. Write a function that adds a timeout to an IO:
   *    - IO runs on a fiber
   *    - if the timeout duration passes, then the fiber is canceled
   *    - the method returns an IO[A] which contains
   *      - the original value if the computation is successful before the timeout signal
   *      - the exception if the computation is failed before the timeout signal
   *      - a RuntimeException if it times out (i.e. cancelled by the timeout)
   */
  // 1
  def processResultsFromFiber[A](io: IO[A]): IO[A] = {
    val outcome = for {
      fib <- io.start
      result <- fib.join // an effect which waits for the fiber to terminate
    } yield result
    outcome flatMap {
      case Succeeded(effect) => effect
      case Canceled() => IO.raiseError(new RuntimeException("Task cancelled"))
      case Errored(e) => IO.raiseError(e)
    }
  }

  def testEx1() = {
    val aComputation = IO("starting").myDebug >> IO.sleep(1.second) >> IO("done!").myDebug >> IO(42)
    processResultsFromFiber(aComputation).void
  }


  // 2
  def tupleIOs[A, B](ioa: IO[A], iob: IO[B]): IO[(A, B)] = {
    val outcome = for {
      fiba <- ioa.start
      fibb <- iob.start
      resulta <- fiba.join
      resultb <- fibb.join
    } yield (resulta, resultb)
    outcome flatMap {
      case (Succeeded(e1), Succeeded(e2)) =>
        for {
          a <- e1
          b <- e2
        } yield (a, b)
        // eq. IO.both(e1, e2)
      case (Errored(e), _)  => IO.raiseError(e)
      case (_, Errored(e))  => IO.raiseError(e)
      case (Canceled(), Canceled()) => IO.raiseError(new RuntimeException("Both are cancelled"))
    }
  }

  def testEx2_1() = {
    val aComputation = IO("starting").myDebug >> IO.sleep(1.second) >> IO("done!").myDebug >> IO(42).myDebug
    tupleIOs(aComputation, aComputation).void
  }

  def testEx2_2() = {
    val aComputation = IO("starting").myDebug >> IO.sleep(1.second) >> IO("done!").myDebug >> IO(42).myDebug
    val anErrorIO = IO.sleep(500.millis) >> IO.raiseError(new RuntimeException("Timeout !!!"))
    tupleIOs(aComputation, anErrorIO).void
  }

  def testEx2() = {
    val firstIO = IO.sleep(2.seconds) >> IO(1).myDebug
    val secondIO = IO.sleep(3.seconds) >> IO(2).myDebug
    tupleIOs(firstIO, secondIO).myDebug.void
  }

  // 3
  def timeout[A](io: IO[A], duration: FiniteDuration): IO[A] = {
    val outcome = for {
      fib <- io.start
      _ <- (IO.sleep(duration) >> fib.cancel).start // careful - fibers can leak
      result <- fib.join // an effect which waits for the fiber to terminate
    } yield result
    outcome flatMap {
      case Succeeded(effect) => effect
      case Canceled() => IO.raiseError(new RuntimeException("Task cancelled"))
      case Errored(e) => IO.raiseError(e)
    }
  }

  def testEx3() = {
    val aComputation = IO("starting").myDebug >> IO.sleep(1.second) >> IO("done!").myDebug >> IO(42)
    timeout(aComputation, 2500.millis).myDebug.void
  }

  override def run: IO[Unit] = {
    sameThreadIos()
    differentThreadIos()
    runOnSomeOtherThread(meaningOfLife)  //IO(Succeeded(IO(42)))
      .myDebug.void

    throwOnAnotherThread()
      .myDebug.void
    testCancel()
      .myDebug.void

    testEx1()
    testEx2_1()
    testEx2_2()
    testEx2()
    testEx3()
  }
}
