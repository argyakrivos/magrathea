package com.blinkbox.books.marvin.magrathea.api

import akka.actor.ActorRefFactory
import com.blinkbox.books.logging.DiagnosticExecutionContext
import com.blinkbox.books.marvin.magrathea.message.DocumentDao
import com.blinkbox.books.marvin.magrathea.{SchemaConfig, ServiceConfig}
import com.blinkbox.books.spray.v1.Error
import com.blinkbox.books.spray.{Directives => CommonDirectives, _}
import org.slf4j.LoggerFactory
import spray.http.HttpHeaders.RawHeader
import spray.http.StatusCodes._
import spray.routing._
import spray.util.LoggingContext

import scala.util.control.NonFatal

trait RestRoutes extends HttpService {
  def getLatestBookById: Route
  def reIndexBook: Route
  def getLatestContributorById: Route
  def reIndexContributor: Route
  def search: Route
  def reIndexLatestSearch: Route
  def reIndexHistorySearch: Route
}

class RestApi(config: ServiceConfig, schemas: SchemaConfig, documentDao: DocumentDao, indexService: IndexService)
  (implicit val actorRefFactory: ActorRefFactory) extends RestRoutes with CommonDirectives with v2.JsonSupport {

  implicit val ec = DiagnosticExecutionContext(actorRefFactory.dispatcher)
  implicit val timeout = config.api.timeout
  implicit val log = LoggerFactory.getLogger(classOf[RestApi])

  override val getLatestBookById = get {
    path("books" / Segment) { id =>
      onSuccess(documentDao.getLatestDocumentById(id, Option(schemas.book))) {
        case Some(doc) => uncacheable(doc)
        case _ => uncacheable(NotFound, Error("NotFound", "The requested book was not found."))
      }
    }
  }

  override val reIndexBook = put {
    path("books" / Segment / "reindex") { id =>
      onSuccess(indexService.reIndexLatestDocument(id, schemas.book)) { found =>
        if (found) uncacheable(OK, None)
        else uncacheable(NotFound, Error("NotFound", "The requested book was not found."))
      }
    }
  }

  override val getLatestContributorById = get {
    path("contributors" / Segment) { id =>
      onSuccess(documentDao.getLatestDocumentById(id, Option(schemas.contributor))) {
        case Some(doc) => uncacheable(doc)
        case _ => uncacheable(NotFound, Error("NotFound", "The requested contributor was not found."))
      }
    }
  }

  override val reIndexContributor = put {
    path("contributors" / Segment / "reindex") { id =>
      onSuccess(indexService.reIndexLatestDocument(id, schemas.contributor)) { found =>
        if (found) uncacheable(OK, None)
        else uncacheable(NotFound, Error("NotFound", "The requested contributor was not found."))
      }
    }
  }

  override val search = get {
    path("search") {
      parameter('q) { q =>
        paged(defaultCount = 50) { paged =>
          onSuccess(indexService.searchInLatest(q, paged))(uncacheable(_))
        }
      }
    }
  }

  override val reIndexLatestSearch = put {
    path("search" / "reindex" / "latest") {
      dynamic {
        log.info("Starting re-indexing of 'latest'...")
        indexService.reIndexLatest().onComplete {
          case scala.util.Success(_) => log.info("Re-indexing of 'latest' finished successfully.")
          case scala.util.Failure(e) => log.error("Re-indexing of 'latest' failed.", e)
        }
        uncacheable(Accepted, None)
      }
    }
  }

  override val reIndexHistorySearch = put {
    path("search" / "reindex" / "history") {
      dynamic {
        log.info("Starting re-indexing of 'history'...")
        indexService.reIndexHistory().onComplete {
          case scala.util.Success(_) => log.info("Re-indexing of 'history' finished successfully.")
          case scala.util.Failure(e) => log.error("Re-indexing of 'history' failed.", e)
        }
        uncacheable(Accepted, None)
      }
    }
  }

  val routes = rootPath(config.api.localUrl.path) {
    monitor() {
      respondWithHeader(RawHeader("Vary", "Accept, Accept-Encoding")) {
        handleExceptions(exceptionHandler) {
          getLatestBookById ~ getLatestContributorById ~ search ~
          reIndexBook ~ reIndexContributor ~ reIndexLatestSearch ~ reIndexHistorySearch
        }
      }
    }
  }

  private def exceptionHandler(implicit log: LoggingContext) = ExceptionHandler {
    case NonFatal(e) =>
      log.error(e, "Unhandled error")
      uncacheable(InternalServerError, None)
  }
}
