/*
 * Copyright 2021 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.gform.wshttp

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.typesafe.config.Config
import play.api.libs.ws.WSClient
import play.api.mvc.MultipartFormData.FilePart
import scala.concurrent.{ ExecutionContext, Future }
import uk.gov.hmrc.http.hooks.HttpHooks
import uk.gov.hmrc.play.audit.http.HttpAuditing
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.http.ws._
import uk.gov.hmrc.http._

trait WSHttp
    extends HttpGet with WSGet with HttpPut with WSPut with HttpPost with WSPost with HttpDelete with WSDelete {
  def POSTFile(
    url: String,
    fileName: String,
    body: ByteString,
    headers: Seq[(String, String)],
    contentType: String
  )(implicit ec: ExecutionContext): Future[HttpResponse]

  def getByteString(
    url: String
  )(implicit ec: ExecutionContext): Future[ByteString]
}

class WSHttpImpl(
  val appName: String,
  val auditConnector: AuditConnector,
  val configuration: Config,
  val actorSystem: ActorSystem,
  val wsClient: WSClient
) extends WSHttp with HttpHooks with HttpAuditing {
  override val hooks = Seq(AuditingHook)

  override def POSTFile(
    url: String,
    fileName: String,
    body: ByteString,
    headers: Seq[(String, String)],
    contentType: String
  )(implicit ec: ExecutionContext): Future[HttpResponse] = {

    val source: Source[FilePart[Source[ByteString, NotUsed]], NotUsed] = Source(
      FilePart(fileName, fileName, Some(contentType), Source.single(body)) :: Nil
    )

    buildRequest(url, headers)
      .post(source)
      .map(wsResponse =>
        HttpResponse(
          status = wsResponse.status,
          body = wsResponse.body,
          headers = wsResponse.headers
        )
      )
  }

  override def getByteString(
    url: String
  )(implicit ec: ExecutionContext): Future[ByteString] =
    buildRequest(url, Seq.empty).get().map(_.bodyAsBytes)
}
