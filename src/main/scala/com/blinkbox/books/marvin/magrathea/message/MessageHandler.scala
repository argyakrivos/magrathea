package com.blinkbox.books.marvin.magrathea.message

import akka.actor.ActorRef
import akka.util.Timeout
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.marvin.magrathea.Json4sExtensions._
import com.blinkbox.books.messaging.{ErrorHandler, Event, ReliableEventHandler}
import com.typesafe.scalalogging.slf4j.StrictLogging
import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods
import spray.can.Http.ConnectionException
import spray.httpx.Json4sJacksonSupport

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, TimeoutException}
import scala.language.{implicitConversions, postfixOps}

class MessageHandler(documentDao: DocumentDao, errorHandler: ErrorHandler, retryInterval: FiniteDuration)
                    (documentMerge: (JValue, JValue) => JValue) extends ReliableEventHandler(errorHandler, retryInterval)
  with StrictLogging with Json4sJacksonSupport with JsonMethods {

  implicit val timeout = Timeout(retryInterval)
  implicit val json4sJacksonFormats = DefaultFormats
  private val md = java.security.MessageDigest.getInstance("SHA-1")

  override protected def handleEvent(event: Event, originalSender: ActorRef) = for {
    incomingDoc <- parseDocument(event.body.asString())
    historyLookupKey = extractHistoryLookupKey(incomingDoc)
    historyLookupKeyMatches <- documentDao.lookupHistoryDocument(historyLookupKey)
    normalisedIncomingDoc = normaliseHistoryDocument(incomingDoc, historyLookupKeyMatches)
    _ <- normaliseDatabase(historyLookupKeyMatches)(documentDao.deleteHistoryDocuments)
    _ <- documentDao.storeHistoryDocument(normalisedIncomingDoc)
    (schema, classification) = extractSchemaAndClassification(normalisedIncomingDoc)
    history <- documentDao.fetchHistoryDocuments(schema, classification)
    mergedDoc = mergeDocuments(history)
    latestLookupKey = extractLatestLookupKey(mergedDoc)
    latestLookupKeyMatches <- documentDao.lookupLatestDocument(latestLookupKey)
    normalisedMergedDoc = normaliseDocument(mergedDoc, latestLookupKeyMatches)
    _ <- normaliseDatabase(latestLookupKeyMatches)(documentDao.deleteLatestDocuments)
    _ <- documentDao.storeLatestDocument(normalisedMergedDoc)
  } yield ()

  // Consider the error temporary if the exception or its root cause is an IO exception, timeout or connection exception.
  @tailrec
  final override protected def isTemporaryFailure(e: Throwable) =
    e.isInstanceOf[TimeoutException] || e.isInstanceOf[ConnectionException] ||
    Option(e.getCause).isDefined && isTemporaryFailure(e.getCause)

  private def sha1(input: String): String = md.digest(input.getBytes("UTF-8")).map("%02x".format(_)).mkString

  private def mergeDocuments(documents: List[JValue]): JValue = {
    if (documents.isEmpty)
      throw new IllegalArgumentException("Expected to merge a non-empty history list")
    val merged = documents.par.reduce(documentMerge)
    logger.info("Merged document")
    merged.removeDirectField("_id").removeDirectField("_rev")
  }

  private def parseDocument(json: String): Future[JValue] = Future {
    logger.info("Received document")
    parse(json)
  }

  private def normaliseHistoryDocument(document: JValue, lookupKeyMatches: List[JValue]): JValue = {
    val doc = normaliseDocument(document, lookupKeyMatches)
    doc \ "contributors" match {
      case JArray(arr) =>
        val newArr: JValue = "contributors" -> arr.map { c =>
          val name = c \ "names" \ "display"
          if (name == JNothing)
            throw new IllegalArgumentException(s"Cannot extract display name from contributor: ${compact(render(c))}")
          val f: JValue = "ids" -> ("bbb" -> sha1(name.extract[String]))
          c merge f
        }
        doc.removeDirectField("contributors") merge newArr
      case _ => doc
    }
  }

  private def normaliseDocument(document: JValue, lookupKeyMatches: List[JValue]): JValue =
    // Merging the value of the first row with the rest of the document. This will result in a document with
    // "_id" and "_rev" fields, which means replace the previous document with this new one.
    // If the first row does not exist, the document will remain unchanged.
    lookupKeyMatches.headOption match {
      case Some(item) =>
        logger.debug("Normalised document to override")
        val idRev = extractIdRev(item)
        document merge (("_id" -> idRev._1) ~ ("_rev" -> idRev._2))
      case None => document
    }

  private def normaliseDatabase(lookupKeyMatches: List[JValue])(f: List[(String, String)] => Future[Unit]): Future[Unit] =
    // If there is at least on document with that key, delete all these documents except the first
    if (lookupKeyMatches.size > 1) f(lookupKeyMatches.drop(1).map(extractIdRev)) else Future.successful(())

  private def extractIdRev(item: JValue): (String, String) = {
    val id = item \ "value" \ "_id"
    val rev = item \ "value" \ "_rev"
    if (id == JNothing || rev == JNothing)
      throw new IllegalArgumentException(s"Cannot extract _id and _rev: ${compact(render(item))}")
    (id.extract[String], rev.extract[String])
  }

  private def extractHistoryLookupKey(document: JValue): String = {
    val schema = document \ "$schema"
    val remaining = (document \ "source" \ "$remaining")
      .removeDirectField("processedAt").removeDirectField("system")
    val classification = document \ "classification"
    if (schema == JNothing || remaining == JNothing || classification == JNothing)
      throw new IllegalArgumentException(
        s"Cannot extract history lookup key (schema, remaining, classification): ${compact(render(document))}")
    val key = compact(render(List(schema, remaining, classification)))
    logger.debug("Extracted history lookup key: {}", key)
    key
  }

  private def extractLatestLookupKey(document: JValue): String = {
    val schema = document \ "$schema"
    val classification = document \ "classification"
    if (schema == JNothing || classification == JNothing)
      throw new IllegalArgumentException(
        s"Cannot extract latest lookup key (schema, classification): ${compact(render(document))}")
    val key = compact(render(("$schema" -> schema) ~ ("classification" -> classification)))
    logger.debug("Extracted latest lookup key: {}", key)
    key
  }

  private def extractSchemaAndClassification(document: JValue): (String, String) = {
    val schema = document \ "$schema"
    val classification = document \ "classification"
    if (schema == JNothing || classification == JNothing)
      throw new IllegalArgumentException(s"Cannot extract schema and classification: ${compact(render(document))}")
    (schema.extract[String], compact(render(classification)))
  }
}