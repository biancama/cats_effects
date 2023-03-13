package part3concurrency

import cats.effect.{IO, IOApp}
import scala.concurrent.duration._

object CancellingIOs extends IOApp.Simple {
  /*
      Cancelling IOs
      - fib.cancel
      - IO.race & other APIs
      - manual cancellation
     */
  import utils._
  val chainOfIos:IO[Int] = IO("waiting").myDebug >> IO.canceled >> IO(42).myDebug

  // uncancelable
  // example: online store, payment processor
  // payment process must NOT be canceled
  val specialPaymentSystem = (
    IO("Payment running, don't cancel me...").myDebug >>
      IO.sleep(1.second) >>
      IO("Payment completed.").myDebug
    ).onCancel(IO("MEGA CANCEL OF DOOM!").myDebug.void)

  val cancellationOfDoom = for {
    fib <- specialPaymentSystem.start
    _ <- IO.sleep(500.millis) >> fib.cancel
    _ <- fib.join
  } yield ()

  val atomicPayment = IO.uncancelable(_ => specialPaymentSystem) // masking
  val atomicPayment_v2 = specialPaymentSystem.uncancelable // same

  val noCancellationOfDoom = for {
    fib <- atomicPayment.start
    _ <- IO.sleep(500.millis) >> IO("Attempting cancellation...").myDebug >> fib.cancel
    _ <- fib.join
  } yield ()
  /*
      The uncancelable API is more complex and more general.
      It takes a function from Poll[IO] to IO. In the example above, we aren't using that Poll instance.
      The Poll object can be used to mark sections within the returned effect which CAN BE CANCELED.
     */

  /*
      Example: authentication service. Has two parts:
      - input password, can be cancelled, because otherwise we might block indefinitely on user input
      - verify password, CANNOT be cancelled once it's started
     */

  val inputPassword = IO("Input password:").myDebug >> IO("(typing password)").myDebug >> IO.sleep(5.seconds) >> IO("RockTheJVM1!")
  val verifyPassword = (pw: String) => IO("verifying...").myDebug >> IO.sleep(2.seconds) >> IO(pw == "RockTheJVM1!")
  val authFlow: IO[Unit] = IO.uncancelable { poll =>
    for {
      pw <- poll(inputPassword).onCancel(IO("Authentication timed out. Try again later.").myDebug.void) // this is cancelable because poll will unmask
      verified <- verifyPassword(pw)
      _ <- if (verified) IO("Authentication successful.").myDebug // this is NOT cancelable
                    else IO("Authentication failed.").myDebug
    } yield ()
  }
  val authProgram = for {
    authFib <- authFlow.start
    _ <- IO.sleep(3.seconds) >> IO("Authentication timeout, attempting cancel....").myDebug >> authFib.cancel
    - <- authFib.join
  } yield ()

  /*
      Uncancelable calls are MASKS which suppress cancellation.
      Poll calls are "gaps opened" in the uncancelable region.
     */

  /**
   * Exercises: what do you think the following effects will do?
   * 1. Anticipate
   * 2. Run to see if you're correct
   * 3. Prove your theory
   */
  // 1
  val cancelBeforeMol = IO.canceled >> IO(42).myDebug
  val uncancelableMol = IO.uncancelable(_ => IO.canceled >> IO(42).myDebug)
  // uncancelable will eliminate ALL cancel points

  // 2
  val invincibleAuthProgram = for {
    authFib <- IO.uncancelable(_ => authFlow).start
    _ <- IO.sleep(1.seconds) >> IO("Authentication timeout, attempting cancel...").myDebug >> authFib.cancel
    _ <- authFib.join
  } yield ()

  /*
    Lesson: Uncancelable calls are masks which suppress all existing cancelable gaps (including from a previous uncancelable).
   */
  def threeStepProgram(): IO[Unit] = {
    val sequence = IO.uncancelable { poll =>
      poll(IO("cancelable").myDebug >> IO.sleep(1.second) >> IO("cancelable end").myDebug) >>
        IO("uncancelable").myDebug >> IO.sleep(1.second) >> IO("uncancelable end").myDebug >>
        poll(IO("second cancelable").myDebug >> IO.sleep(1.second) >> IO("second cancelable end").myDebug)
    }

    for {
      fib <- sequence.start
      _ <- IO.sleep(1500.millis) >> IO("CANCELING").myDebug >> fib.cancel
      _ <- fib.join
    } yield ()
  }

  /*
      Lesson: Uncancelable regions ignore cancellation signals, but that doesn't mean the next CANCELABLE region won't take them.
     */
  // override def run: IO[Unit] = cancellationOfDoom.void
  // override def run: IO[Unit] = noCancellationOfDoom
  //override def run: IO[Unit] = authFlow
  override def run: IO[Unit] = authProgram
}
