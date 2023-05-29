package udemy.part3ConcurrencyAndParallelism

import cats.effect.IO
import cats.effect.IO.{IOCont, Uncancelable}
import cats.syntax.applicative.*
import cats.syntax.traverse.*
import cats.syntax.parallel.*
import cats.syntax.all._
case class Person (name: String)
class PersonService {
  def createPerson(name: String): IO[Person] = Person(name).pure[IO]  // from applicative

  def createAll(names: List[String]): IO[List[Person]] = names.map(createPerson).sequence  // from traverse
  def createAll01(names: List[String]): IO[List[Person]] = names.traverse(createPerson)  // from traverse

  def createAllParallel(names: List[String]): IO[List[Person]] = names.parTraverse(createPerson)  // from traverse


}


case class Quote(author: String, text: String)

class QuoteService {
  def kittensQuotes(n: Int): IO[List[Quote]] = ???
  def puppiesQuotes(n: Int): IO[List[Quote]] = ???
  def mixedQuotes01(n: Int): IO[List[Quote]] = for {
    kittens <- kittensQuotes(n)
    puppies <- puppiesQuotes(n)
  } yield kittens ++ puppies

  def mixedQuotes(n: Int): IO[List[Quote]] = (kittensQuotes(n), puppiesQuotes(n)).mapN((k, q) => k ++ q)

  def mixedQuotesPar(n: Int): IO[List[Quote]] = (kittensQuotes(n), puppiesQuotes(n)).parMapN((k, q) => k ++ q)

}

case class Image(data: List[Byte])
class ImagesService {
  def fetchFromDb(n: Int): IO[List[Image]] = ???
  def fetchFromHttp(n: Int): IO[List[Image]] = ???
  def fetchFromFastest(n: Int): IO[List[Image]] = IO.race(fetchFromDb(n), fetchFromHttp(n)) flatMap  {
    case Left(db) => db.pure[IO]
    case Right(http) =>  http.pure[IO]
  }
}