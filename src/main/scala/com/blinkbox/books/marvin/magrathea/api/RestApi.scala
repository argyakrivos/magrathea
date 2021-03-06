package com.blinkbox.books.marvin.magrathea.api

import java.util.UUID

import akka.actor.ActorRefFactory
import com.blinkbox.books.config.ApiConfig
import com.blinkbox.books.logging.DiagnosticExecutionContext
import com.blinkbox.books.marvin.magrathea.message.{DocumentDao, DocumentRevisions}
import com.blinkbox.books.marvin.magrathea.{JsonDoc, SchemaConfig}
import com.blinkbox.books.spray.v2.Error
import com.blinkbox.books.spray.v2.Implicits.throwableMarshaller
import com.blinkbox.books.spray.v2.RejectionHandler.ErrorRejectionHandler
import com.blinkbox.books.spray.{Directives => CommonDirectives, _}
import com.typesafe.scalalogging.StrictLogging
import spray.http.AllOrigins
import spray.http.HttpHeaders.{RawHeader, `Access-Control-Allow-Origin`}
import spray.http.StatusCodes._
import spray.routing._
import spray.util.LoggingContext

import scala.util.Try
import scala.util.control.NonFatal

trait RestRoutes extends HttpService {
  def getCurrentBookById: Route
  def getCurrentBookHistory: Route
  def reIndexBook: Route
  def getCurrentContributorById: Route
  def getCurrentContributorHistory: Route
  def reIndexContributor: Route
  def search: Route
  def reIndexCurrentSearch: Route
  def reIndexHistorySearch: Route
}

class RestApi(config: ApiConfig, schemas: SchemaConfig, documentDao: DocumentDao, indexService: IndexService)
  (implicit val actorRefFactory: ActorRefFactory) extends RestRoutes with CommonDirectives with v2.JsonSupport with StrictLogging {

  implicit val ec = DiagnosticExecutionContext(actorRefFactory.dispatcher)
  implicit val timeout = config.timeout

  private val bookError = uncacheable(NotFound, Error("NotFound", Some("The requested book was not found.")))
  private val contributorError = uncacheable(NotFound, Error("NotFound", Some("The requested contributor was not found.")))
  private val uuidError = uncacheable(BadRequest, Error("InvalidUUID", Some("The requested id is not a valid UUID.")))

  override val getCurrentBookById = get {
    path("books" / Segment) { id =>
      withUUID(id) { uuid =>
        onSuccess(documentDao.getCurrentDocumentById(uuid, Option(schemas.book))) {
          _.fold(bookError)(docResponse)
        }
      }
    }
  }

  override val getCurrentBookHistory = get {
    path("books" / Segment / "history") { id =>
      withUUID(id) { uuid =>
        onSuccess(documentDao.getDocumentHistory(uuid, schemas.book).map(DocumentRevisions)) { history =>
          if (history.size > 0) uncacheable(history) else bookError
        }
      }
    }
  }

  override val reIndexBook = put {
    path("books" / Segment / "reindex") { id =>
      withUUID(id) { uuid =>
        onSuccess(indexService.reIndexCurrentDocument(uuid, schemas.book)) { found =>
          if (found) uncacheable(OK, None) else bookError
        }
      }
    }
  }

  override val getCurrentContributorById = get {
    path("contributors" / Segment) { id =>
      withUUID(id) { uuid =>
        onSuccess(documentDao.getCurrentDocumentById(uuid, Option(schemas.contributor))) {
          _.fold(contributorError)(docResponse)
        }
      }
    }
  }

  override val getCurrentContributorHistory = get {
    path("contributors" / Segment / "history") { id =>
      withUUID(id) { uuid =>
        onSuccess(documentDao.getDocumentHistory(uuid, schemas.contributor).map(DocumentRevisions)) { history =>
          if (history.size > 0) uncacheable(history) else contributorError
        }
      }
    }
  }

  override val reIndexContributor = put {
    path("contributors" / Segment / "reindex") { id =>
      withUUID(id) { uuid =>
        onSuccess(indexService.reIndexCurrentDocument(uuid, schemas.contributor)) { found =>
          if (found) uncacheable(OK, None) else contributorError
        }
      }
    }
  }

  override val search = get {
    path("search") {
      parameter('q) { q =>
        paged(defaultCount = 50) { paged =>
          onSuccess(indexService.searchInCurrent(q, paged))(uncacheable(_))
        }
      }
    }
  }

  override val reIndexCurrentSearch = put {
    path("search" / "reindex" / "current") {
      dynamic {
        logger.info("Starting re-indexing of 'current'...")
        indexService.reIndexCurrent().onComplete {
          case scala.util.Success(_) => logger.info("Re-indexing of 'current' finished successfully.")
          case scala.util.Failure(e) => logger.error("Re-indexing of 'current' failed.", e)
        }
        uncacheable(Accepted, None)
      }
    }
  }

  override val reIndexHistorySearch = put {
    path("search" / "reindex" / "history") {
      dynamic {
        logger.info("Starting re-indexing of 'history'...")
        indexService.reIndexHistory().onComplete {
          case scala.util.Success(_) => logger.info("Re-indexing of 'history' finished successfully.")
          case scala.util.Failure(e) => logger.error("Re-indexing of 'history' failed.", e)
        }
        uncacheable(Accepted, None)
      }
    }
  }

  val routes = rootPath(config.localUrl.path) {
    monitor(logger, throwableMarshaller) {
      respondWithHeaders(
        RawHeader("Vary", "Accept, Accept-Encoding"),
        `Access-Control-Allow-Origin`(AllOrigins)
      ) {
        handleExceptions(exceptionHandler) {
          handleRejections(ErrorRejectionHandler) {
            getCurrentBookById ~ getCurrentBookHistory ~ getCurrentContributorById ~ getCurrentContributorHistory ~
            search ~ reIndexBook ~ reIndexContributor ~ reIndexCurrentSearch ~ reIndexHistorySearch
          }
        }
      }
    }
  }

  private def exceptionHandler(implicit log: LoggingContext) = ExceptionHandler {
    case NonFatal(e) =>
      logger.error("Unhandled error", e)
      uncacheable(InternalServerError, None)
  }

  private def withUUID(rawId: String): Directive1[UUID] =
    Try(UUID.fromString(rawId)).toOption.fold[Directive1[UUID]](uuidError)(provide)

  private def docResponse = (doc: JsonDoc) => uncacheable(doc.toJson)
}
