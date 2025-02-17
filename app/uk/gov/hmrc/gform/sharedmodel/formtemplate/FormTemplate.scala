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

package uk.gov.hmrc.gform.sharedmodel.formtemplate

import cats.data.NonEmptyList
import julienrf.json.derived
import play.api.libs.json._
import uk.gov.hmrc.gform.sharedmodel.email.LocalisedEmailTemplateId
import uk.gov.hmrc.gform.sharedmodel.{ AvailableLanguages, LocalisedString, formtemplate }
import uk.gov.hmrc.gform.sharedmodel.formtemplate.destinations.Destinations

case class FormTemplate(
  _id: FormTemplateId,
  originalId: FormTemplateId,
  formName: LocalisedString,
  developmentPhase: Option[DevelopmentPhase],
  formCategory: FormCategory,
  draftRetrievalMethod: DraftRetrievalMethod,
  destinations: Destinations,
  authConfig: formtemplate.AuthConfig,
  emailTemplateId: LocalisedEmailTemplateId,
  emailParameters: Option[NonEmptyList[EmailParameter]],
  webChat: Option[WebChat],
  sections: List[Section],
  parentFormSubmissionRefs: List[FormComponentId],
  languages: AvailableLanguages,
  save4LaterInfoText: Option[Save4LaterInfoText],
  summarySection: SummarySection,
  displayHMRCLogo: Boolean,
  userResearchUrl: Option[UserResearchUrl] = None,
  referrerConfig: Option[ReferrerConfig] = None
) {

  val isSpecimen: Boolean = _id.value.startsWith("specimen-")

}

object FormTemplate {

  import JsonUtils._

  private val reads = Reads[FormTemplate] { json =>
    Json.reads[FormTemplate].reads(json)
  }

  implicit val format: OFormat[FormTemplate] = OFormat(reads, derived.owrites[FormTemplate]())
}
