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

package uk.gov.hmrc.gform.gform

import cats.implicits._
import play.api.i18n.I18nSupport
import play.api.mvc.MessagesControllerComponents
import uk.gov.hmrc.gform.config.FrontendAppConfig
import uk.gov.hmrc.gform.controllers.NonAuthenticatedRequestActions
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController
import uk.gov.hmrc.gform.views.html

import scala.concurrent.{ ExecutionContext, Future }

class ExampleController(
  i18nSupport: I18nSupport,
  frontendAppConfig: FrontendAppConfig,
  nonAuthenticatedRequestActions: NonAuthenticatedRequestActions,
  messagesControllerComponents: MessagesControllerComponents
)(implicit
  ec: ExecutionContext
) extends FrontendController(messagesControllerComponents) {

  import i18nSupport._

  def example =
    nonAuthenticatedRequestActions.async { implicit request => implicit lang =>
      Ok(
        html.example(frontendAppConfig)
      ).pure[Future]
    }

}
