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

import org.slf4j.LoggerFactory
import play.api.http.HttpEntity
import play.api.i18n.{ I18nSupport, Messages }
import play.api.mvc.{ Action, AnyContent, MessagesControllerComponents, ResponseHeader, Result }
import uk.gov.hmrc.gform.FormTemplateKey
import uk.gov.hmrc.gform.auditing.AuditService
import uk.gov.hmrc.gform.auth.models.{ CompositeAuthDetails, OperationWithForm }
import uk.gov.hmrc.gform.controllers.AuthenticatedRequestActionsAlgebra
import uk.gov.hmrc.gform.controllers.GformSessionKeys.COMPOSITE_AUTH_DETAILS_SESSION_KEY
import uk.gov.hmrc.gform.gform.SessionUtil.jsonFromSession
import uk.gov.hmrc.gform.gformbackend.GformConnector
import uk.gov.hmrc.gform.graph.Recalculation
import uk.gov.hmrc.gform.models.SectionSelectorType
import uk.gov.hmrc.gform.models.optics.DataOrigin
import uk.gov.hmrc.gform.nonRepudiation.NonRepudiationHelpers
import uk.gov.hmrc.gform.sharedmodel.AccessCode
import uk.gov.hmrc.gform.sharedmodel.form._
import uk.gov.hmrc.gform.sharedmodel.formtemplate._
import uk.gov.hmrc.gform.graph.CustomerIdRecalculation
import uk.gov.hmrc.gform.pdf.PDFRenderService
import uk.gov.hmrc.gform.pdf.model.{ PDFModel, PDFType }
import uk.gov.hmrc.gform.sharedmodel.form.EmailAndCode.toJsonStr
import uk.gov.hmrc.gform.sharedmodel.formtemplate.destinations.Destinations.DestinationList
import uk.gov.hmrc.gform.summarypdf.PdfGeneratorService
import uk.gov.hmrc.gform.summary.{ SubmissionDetails, SummaryRenderingService }
import uk.gov.hmrc.http.BadRequestException
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController

import scala.concurrent.{ ExecutionContext, Future }

class AcknowledgementController(
  i18nSupport: I18nSupport,
  auth: AuthenticatedRequestActionsAlgebra[Future],
  pdfService: PdfGeneratorService,
  pdfRenderService: PDFRenderService,
  renderer: SectionRenderingService,
  summaryRenderingService: SummaryRenderingService,
  gformConnector: GformConnector,
  nonRepudiationHelpers: NonRepudiationHelpers,
  messagesControllerComponents: MessagesControllerComponents,
  recalculation: Recalculation[Future, Throwable],
  auditService: AuditService
)(implicit ec: ExecutionContext)
    extends FrontendController(messagesControllerComponents) {

  private val logger = LoggerFactory.getLogger(getClass)

  def showAcknowledgement(
    maybeAccessCode: Option[AccessCode],
    formTemplateId: FormTemplateId
  ): Action[AnyContent] =
    auth.authAndRetrieveForm[SectionSelectorType.Normal](
      formTemplateId,
      maybeAccessCode,
      OperationWithForm.ViewAcknowledgement
    ) { implicit request => implicit l => cache => implicit sse => formModelOptics =>
      import i18nSupport._
      val formTemplate = request.attrs(FormTemplateKey)
      val compositeAuthDetails: CompositeAuthDetails =
        jsonFromSession(request, COMPOSITE_AUTH_DETAILS_SESSION_KEY, CompositeAuthDetails.empty)

      formTemplate.authConfig match {
        case Composite(_) =>
          logger.info(
            s"For a template, ${formTemplateId.value} with composite config user has selected " +
              s"${AuthConfig.authConfigNameInLogs(compositeAuthDetails.get(formTemplateId).getOrElse(""))} config " +
              s"and submitted a form with envelopeId ${cache.form.envelopeId}"
          )
        case config =>
          logger.info(
            s"For a template, ${formTemplateId.value} with ${AuthConfig.authConfigNameInLogs(config.authConfigName)} config " +
              s"user has submitted a form with envelopeId ${cache.form.envelopeId}"
          )
      }

      val sessionAfterRemovingCompositeAuthDetails = request.session
        .+(COMPOSITE_AUTH_DETAILS_SESSION_KEY -> toJsonStr(compositeAuthDetails.remove(formTemplateId)))

      cache.formTemplate.destinations match {
        case destinationList: DestinationList =>
          Future.successful(
            Ok(
              renderer
                .renderAcknowledgementSection(
                  maybeAccessCode,
                  cache.formTemplate,
                  destinationList,
                  cache.retrievals,
                  cache.form.envelopeId,
                  formModelOptics
                )
            ).withSession(sessionAfterRemovingCompositeAuthDetails)
          )
        case _ =>
          Future.failed(new BadRequestException(s"Acknowledgement is not defined for $formTemplateId"))
      }
    }

  def downloadPDF(maybeAccessCode: Option[AccessCode], formTemplateId: FormTemplateId): Action[AnyContent] =
    auth.authAndRetrieveForm[SectionSelectorType.WithAcknowledgement](
      formTemplateId,
      maybeAccessCode,
      OperationWithForm.ViewAcknowledgement
    ) { implicit request => implicit l => cache => implicit sse => formModelOptics =>
      import i18nSupport._
      val messages: Messages = request2Messages(request)
      val formString = nonRepudiationHelpers.formDataToJson(cache.form)
      val hashedValue = nonRepudiationHelpers.computeHash(formString)

      val customerId = CustomerIdRecalculation
        .evaluateCustomerId[DataOrigin.Mongo, SectionSelectorType.WithAcknowledgement](
          cache,
          formModelOptics.formModelVisibilityOptics
        )

      val eventId = auditService
        .calculateSubmissionEvent(cache.form, formModelOptics.formModelVisibilityOptics, cache.retrievals, customerId)
        .eventId

      import i18nSupport._
      for {

        _ <- nonRepudiationHelpers.sendAuditEvent(hashedValue, formString, eventId)
        submission <- gformConnector.submissionDetails(
                        FormIdData(cache.retrievals, formTemplateId, maybeAccessCode),
                        cache.form.envelopeId
                      )

        maybePDFHeaderFooter = cache.formTemplate.destinations match {
                                 case d: DestinationList => d.acknowledgementSection.pdf.map(p => (p.header, p.footer))
                                 case _                  => None
                               }

        pdfHtml <-
          pdfRenderService
            .createPDFHtml[DataOrigin.Mongo, SectionSelectorType.WithAcknowledgement, PDFType.Summary](
              s"${messages("summary.acknowledgement.pdf")} - ${cache.formTemplate.formName.value}",
              None,
              cache,
              formModelOptics,
              maybePDFHeaderFooter.map { case (maybeHeader, maybeFooter) =>
                PDFModel.HeaderFooter(maybeHeader, maybeFooter)
              },
              Some(SubmissionDetails(submission, hashedValue)),
              SummaryPagePurpose.ForUser
            )
        pdfSource <- pdfService.generatePDFLocal(pdfHtml)
      } yield Result(
        header = ResponseHeader(200, Map.empty),
        body = HttpEntity.Streamed(pdfSource, None, Some("application/pdf"))
      )
    }

  def exitSurvey(formTemplateId: FormTemplateId, maybeAccessCode: Option[AccessCode]): Action[AnyContent] =
    Action.async { request =>
      Future.successful(Redirect(s"/feedback/${formTemplateId.value}").withNewSession)
    }

}
