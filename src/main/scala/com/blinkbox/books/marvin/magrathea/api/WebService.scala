package com.blinkbox.books.marvin.magrathea.api

import com.blinkbox.books.logging.DiagnosticExecutionContext
import com.blinkbox.books.marvin.magrathea.AppConfig
import com.blinkbox.books.marvin.magrathea.message.DocumentDao
import com.blinkbox.books.spray.HealthCheckHttpService
import spray.http.Uri.Path
import spray.routing.HttpServiceActor

class WebService(config: AppConfig, documentDao: DocumentDao, indexService: IndexService) extends HttpServiceActor {
  implicit val ec = DiagnosticExecutionContext(actorRefFactory.dispatcher)
  val restApi = new RestApi(config.service, config.schemas, documentDao, indexService)
  val healthService = new HealthCheckHttpService {
    override implicit def actorRefFactory = WebService.this.actorRefFactory
    override val basePath = Path./
  }

  def receive = runRoute(restApi.routes ~ healthService.routes)
}
