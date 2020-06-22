/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.gform.summary

import java.time.format.DateTimeFormatter

import cats.data.NonEmptyList
import cats.data.Validated.{ Invalid, Valid }
import cats.instances.future._
import org.jsoup.Jsoup
import play.api.i18n.{ I18nSupport, Messages }
import play.api.mvc.Request
import play.twirl.api.{ Html, HtmlFormat }
import uk.gov.hmrc.gform.auth.models.MaterialisedRetrievals
import uk.gov.hmrc.gform.commons.MarkDownUtil.markDownParser
import uk.gov.hmrc.gform.config.FrontendAppConfig
import uk.gov.hmrc.gform.controllers.AuthCacheWithForm
import uk.gov.hmrc.gform.eval.smartstring._
import uk.gov.hmrc.gform.fileupload.{ Envelope, FileUploadAlgebra }
import uk.gov.hmrc.gform.gform.{ HtmlSanitiser, SummaryPagePurpose }
import uk.gov.hmrc.gform.graph.Recalculation
import uk.gov.hmrc.gform.keystore.RepeatingComponentService
import uk.gov.hmrc.gform.models.ExpandUtils._
import uk.gov.hmrc.gform.models.helpers.Fields.flattenGroups
import uk.gov.hmrc.gform.models.helpers.{ Fields, TaxPeriodHelper }
import uk.gov.hmrc.gform.sharedmodel._
import uk.gov.hmrc.gform.sharedmodel.form.{ FormDataRecalculated, ValidationResult }
import uk.gov.hmrc.gform.sharedmodel.formtemplate.SectionTitle4Ga.sectionTitle4GaFactory
import uk.gov.hmrc.gform.sharedmodel.formtemplate._
import uk.gov.hmrc.gform.sharedmodel.formtemplate.destinations.Destinations.DestinationList
import uk.gov.hmrc.gform.sharedmodel.formtemplate.destinations.PrintSection
import uk.gov.hmrc.gform.sharedmodel.formtemplate.destinations.PrintSection.PdfNotification
import uk.gov.hmrc.gform.summary.SummaryRenderingService.validate
import uk.gov.hmrc.gform.validation.ValidationUtil.ValidatedType
import uk.gov.hmrc.gform.validation.{ FormFieldValidationResult, ValidationService }
import uk.gov.hmrc.gform.views.html.form.snippets.print_pdf_header
import uk.gov.hmrc.gform.views.html.summary.snippets._
import uk.gov.hmrc.gform.views.html.summary.summary
import uk.gov.hmrc.gform.views.summary.SummaryListRowHelper._
import uk.gov.hmrc.govukfrontend.views.viewmodels.summarylist.{ SummaryList, SummaryListRow }
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.gform.views.html.errorInline
import uk.gov.hmrc.gform.views.summary.TextFormatter._
import uk.gov.hmrc.govukfrontend.views.viewmodels.content.HtmlContent
import uk.gov.hmrc.gform.models.helpers.DateHelperFunctions.{ getMonthValue, renderMonth }
import uk.gov.hmrc.gform.models.helpers.TaxPeriodHelper.formatDate
import uk.gov.hmrc.gform.views.summary.TextFormatter
import uk.gov.hmrc.govukfrontend.views.html.components.{ ErrorMessage, govukErrorMessage, govukSummaryList }

import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }

class SummaryRenderingService(
  i18nSupport: I18nSupport,
  fileUploadAlgebra: FileUploadAlgebra[Future],
  recalculation: Recalculation[Future, Throwable],
  validationService: ValidationService,
  frontendAppConfig: FrontendAppConfig) {

  def createHtmlForPdf(
    maybeAccessCode: Option[AccessCode],
    cache: AuthCacheWithForm,
    submissionDetails: Option[SubmissionDetails],
    summaryPagePurpose: SummaryPagePurpose
  )(
    implicit
    request: Request[_],
    l: LangADT,
    hc: HeaderCarrier,
    ec: ExecutionContext,
    lise: SmartStringEvaluator
  ): Future[PdfHtml] = {
    import i18nSupport._

    // ToDo: Why do we sanitise just the summaryHtml and not the whole thing after adding the extra data?
    for {
      summaryHtml <- getSummaryHTML(cache.form.formTemplateId, maybeAccessCode, cache, summaryPagePurpose)
    } yield
      PdfHtml(
        addExtraDataToHTML(
          // ToDo: I'm bothered by this. Why is submitted always true? Why is it not submissionDetails.isDefined?
          // Would it matter if sanitiseHtmlForPDF always did what it does when submitted = true?
          HtmlSanitiser.sanitiseHtmlForPDF(summaryHtml, submitted = true),
          submissionDetails,
          cache
        ))
  }

  def createHtmlForPrintPdf(
    maybeAccessCode: Option[AccessCode],
    cache: AuthCacheWithForm,
    summaryPagePurpose: SummaryPagePurpose,
    pdf: PrintSection.Pdf)(
    implicit request: Request[_],
    l: LangADT,
    hc: HeaderCarrier,
    ec: ExecutionContext,
    lise: SmartStringEvaluator): Future[PdfHtml] = {
    import i18nSupport._
    for {
      summaryHtml <- getSummaryHTML(cache.form.formTemplateId, maybeAccessCode, cache, summaryPagePurpose)
    } yield {
      PdfHtml(
        addDataToPrintPdfHTML(
          HtmlSanitiser.sanitiseHtmlForPDF(summaryHtml, submitted = true),
          cache,
          pdf.header,
          pdf.footer
        ))
    }
  }

  def createHtmlForNotificationPdf(
    maybeAccessCode: Option[AccessCode],
    cache: AuthCacheWithForm,
    summaryPagePurpose: SummaryPagePurpose,
    pdfNotification: PdfNotification)(
    implicit request: Request[_],
    l: LangADT,
    hc: HeaderCarrier,
    ec: ExecutionContext,
    lise: SmartStringEvaluator): Future[PdfHtml] = {
    import i18nSupport._

    val pdfFieldIds = pdfNotification.fieldIds
    val pdfHeader = pdfNotification.header
    val pdfFooter = pdfNotification.footer

    for {
      pdfHtml <- getNotificationPdfHTML(
                  cache.form.formTemplateId,
                  maybeAccessCode,
                  cache,
                  summaryPagePurpose,
                  pdfFieldIds)
    } yield {
      PdfHtml(
        addDataToPrintPdfHTML(HtmlSanitiser.sanitiseHtmlForPDF(pdfHtml, submitted = true), cache, pdfHeader, pdfFooter))
    }
  }

  private def addExtraDataToHTML(html: String, submissionDetails: Option[SubmissionDetails], cache: AuthCacheWithForm)(
    implicit hc: HeaderCarrier,
    messages: Messages,
    curLang: LangADT,
    lise: SmartStringEvaluator): String = {
    val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy")
    val formattedTime = submissionDetails.map(sd =>
      s"""${sd.submission.submittedDate.format(dateFormat)} ${sd.submission.submittedDate.format(timeFormat)}""")

    val rows = List(
      formattedTime.map(ft => cya_row(messages("submission.date"), ft)),
      Some(cya_row(messages("submission.reference"), SubmissionRef(cache.form.envelopeId).toString)),
      submissionDetails.map(sd => cya_row(messages("submission.mark"), sd.hashedValue))
    ).flatten

    val extraData = cya_section(messages("submission.details"), HtmlFormat.fill(rows)).toString()

    val declaration: List[(FormComponent, Seq[String])] = cache.formTemplate.destinations match {
      case destinationList: DestinationList =>
        for {
          formTemplateDecField <- flattenGroups(destinationList.declarationSection.fields)
          formData             <- cache.variadicFormData.get(formTemplateDecField.id)
        } yield (formTemplateDecField, formData.toSeq)

      case _ =>
        Nil
    }

    val declarationExtraData = cya_section(
      messages("submission.declaration.details"),
      HtmlFormat.fill(declaration.map {
        case (formDecFields, formData) => cya_row(formDecFields.label.value, formData.mkString)
      })
    ).toString()

    val headerHtml = pdf_header(cache.formTemplate).toString()

    val doc = Jsoup.parse(html)
    doc.select("article[class*=content__body]").prepend(headerHtml)
    doc.select("article[class*=content__body]").append(extraData)
    doc.select("article[class*=content__body]").append(declarationExtraData)
    doc.html.replace("£", "&pound;")
  }

  private def addDataToPrintPdfHTML(
    html: String,
    cache: AuthCacheWithForm,
    pdfHeader: SmartString,
    pdfFooter: SmartString)(
    implicit hc: HeaderCarrier,
    messages: Messages,
    curLang: LangADT,
    lise: SmartStringEvaluator) = {
    val doc = Jsoup.parse(html)

    val headerHtml =
      print_pdf_header(cache.formTemplate, markDownParser(pdfHeader)).toString

    doc.select("article[class*=content__body]").prepend(headerHtml)
    doc.select("article[class*=content__body]").append(markDownParser(pdfFooter).toString)
    doc.html.replace("£", "&pound;")
  }

  def getSummaryHTML(
    formTemplateId: FormTemplateId,
    maybeAccessCode: Option[AccessCode],
    cache: AuthCacheWithForm,
    summaryPagePurpose: SummaryPagePurpose
  )(
    implicit
    request: Request[_],
    l: LangADT,
    hc: HeaderCarrier,
    ec: ExecutionContext,
    lise: SmartStringEvaluator): Future[Html] = {
    val dataRaw = cache.variadicFormData
    val envelopeF = fileUploadAlgebra.getEnvelope(cache.form.envelopeId)

    import i18nSupport._

    for {
      data <- recalculation
               .recalculateFormData(
                 dataRaw,
                 cache.formTemplate,
                 cache.retrievals,
                 cache.form.thirdPartyData,
                 cache.form.envelopeId)
      envelope <- envelopeF
      (v, _)   <- validationService.validateForm(cache, envelope, cache.retrievals)
    } yield
      SummaryRenderingService.renderSummary(
        cache.formTemplate,
        v,
        data,
        maybeAccessCode,
        envelope,
        cache.retrievals,
        frontendAppConfig,
        cache.form.thirdPartyData.obligations,
        cache.form.thirdPartyData.reviewComments,
        summaryPagePurpose
      )

  }

  def getNotificationPdfHTML(
    formTemplateId: FormTemplateId,
    maybeAccessCode: Option[AccessCode],
    cache: AuthCacheWithForm,
    summaryPagePurpose: SummaryPagePurpose,
    pdfFieldIds: List[FormComponentId]
  )(
    implicit
    request: Request[_],
    l: LangADT,
    hc: HeaderCarrier,
    ec: ExecutionContext,
    lise: SmartStringEvaluator) = {

    val dataRaw: VariadicFormData = cache.variadicFormData
    val envelopeF = fileUploadAlgebra.getEnvelope(cache.form.envelopeId)

    import i18nSupport._

    for {
      data <- recalculation
               .recalculateFormData(
                 dataRaw,
                 cache.formTemplate,
                 cache.retrievals,
                 cache.form.thirdPartyData,
                 cache.form.envelopeId)
      envelope <- envelopeF
      (v, _)   <- validationService.validateForm(cache, envelope, cache.retrievals)
    } yield
      SummaryRenderingService.renderNotificationPdfSummary(
        cache.formTemplate,
        v,
        data,
        maybeAccessCode,
        envelope,
        cache.retrievals,
        frontendAppConfig,
        cache.form.thirdPartyData.obligations,
        cache.form.thirdPartyData.reviewComments,
        summaryPagePurpose,
        pdfFieldIds
      )
  }
}

object SummaryRenderingService {

  def renderSummary(
    formTemplate: FormTemplate,
    validatedType: ValidatedType[ValidationResult],
    formFields: FormDataRecalculated,
    maybeAccessCode: Option[AccessCode],
    envelope: Envelope,
    retrievals: MaterialisedRetrievals,
    frontendAppConfig: FrontendAppConfig,
    obligations: Obligations,
    reviewerComments: Option[String],
    summaryPagePurpose: SummaryPagePurpose
  )(
    implicit
    request: Request[_],
    messages: Messages,
    l: LangADT,
    lise: SmartStringEvaluator): Html = {
    val headerHtml = markDownParser(formTemplate.summarySection.header)
    val footerHtml = markDownParser(formTemplate.summarySection.footer)

    val envelopeUpd =
      summaryPagePurpose match {
        case SummaryPagePurpose.ForUser => envelope.withUserFileNames
        case SummaryPagePurpose.ForDms  => envelope
      }
    val sfr =
      summaryRowsForRender(
        validatedType,
        formFields,
        maybeAccessCode,
        formTemplate,
        envelopeUpd,
        obligations,
        reviewerComments
      )
    summary(
      formTemplate,
      sfr,
      maybeAccessCode,
      formTemplate.formCategory,
      retrievals.renderSaveAndComeBackLater,
      retrievals.continueLabelKey,
      frontendAppConfig,
      summaryPagePurpose,
      reviewerComments,
      headerHtml,
      footerHtml
    )
  }

  def renderNotificationPdfSummary(
    formTemplate: FormTemplate,
    validatedType: ValidatedType[ValidationResult],
    formFields: FormDataRecalculated,
    maybeAccessCode: Option[AccessCode],
    envelope: Envelope,
    retrievals: MaterialisedRetrievals,
    frontendAppConfig: FrontendAppConfig,
    obligations: Obligations,
    reviewerComments: Option[String],
    summaryPagePurpose: SummaryPagePurpose,
    pdfFieldIds: List[FormComponentId]
  )(
    implicit
    request: Request[_],
    messages: Messages,
    l: LangADT,
    lise: SmartStringEvaluator): Html = {
    val headerHtml = markDownParser(formTemplate.summarySection.header)
    val footerHtml = markDownParser(formTemplate.summarySection.footer)
    val sfr =
      summaryForNotificationPdf(
        validatedType,
        formFields,
        maybeAccessCode,
        formTemplate,
        envelope,
        obligations,
        reviewerComments,
        pdfFieldIds
      )
    summary(
      formTemplate,
      sfr,
      maybeAccessCode,
      formTemplate.formCategory,
      retrievals.renderSaveAndComeBackLater,
      retrievals.continueLabelKey,
      frontendAppConfig,
      summaryPagePurpose,
      reviewerComments,
      headerHtml,
      footerHtml
    )
  }

  def summaryForRender(
    validatedType: ValidatedType[ValidationResult],
    data: FormDataRecalculated,
    maybeAccessCode: Option[AccessCode],
    formTemplate: FormTemplate,
    envelope: Envelope,
    obligations: Obligations,
    reviewerComments: Option[String] = None
  )(
    implicit
    messages: Messages,
    l: LangADT,
    lise: SmartStringEvaluator): List[Html] = {

    def renderHtmls(sections: List[Section], fields: List[FormComponent])(implicit l: LangADT): List[Html] = {

      val sectionsToRender =
        sections.zipWithIndex.collect {
          case (section, index) if data.isVisible(section) => (section, index)
        }

      sectionsToRender
        .flatMap {
          case (section, index) =>
            val sectionTitle4Ga = sectionTitle4GaFactory(sections(index), SectionNumber(index))
            val begin = begin_section(section.shortName.getOrElse(section.title).value)
            val end = end_section()

            val middle =
              section.fields
                .filterNot(_.hideOnSummary)
                .map(valueToHtml(
                  _,
                  formTemplate._id,
                  data,
                  maybeAccessCode,
                  section.shortName.getOrElse(section.title).value,
                  SectionNumber(index),
                  sectionTitle4Ga,
                  obligations,
                  fields,
                  validatedType,
                  envelope
                ))
            if (middle.isEmpty) {
              Nil
            } else {
              begin +: middle :+ end
            }
        }
    }

    val sections = RepeatingComponentService.getAllSections(formTemplate, data)

    val fields = sections.flatMap(RepeatingComponentService.atomicFields(_, data.data))

    renderHtmls(sections, fields)
  }

  def summaryRowsForRender(
    validatedType: ValidatedType[ValidationResult],
    data: FormDataRecalculated,
    maybeAccessCode: Option[AccessCode],
    formTemplate: FormTemplate,
    envelope: Envelope,
    obligations: Obligations,
    reviewerComments: Option[String] = None
  )(
    implicit
    messages: Messages,
    l: LangADT,
    lise: SmartStringEvaluator): List[Html] = {

    def renderHtmls(sections: List[Section], fields: List[FormComponent])(implicit l: LangADT): List[Html] = {

      val sectionsToRender =
        sections.zipWithIndex.collect {
          case (section, index) if data.isVisible(section) => (section, index)
        }

      sectionsToRender
        .flatMap {
          case (section, index) =>
            val sectionTitle4Ga = sectionTitle4GaFactory(sections(index), SectionNumber(index))
            val begin = begin_section(section.shortName.getOrElse(section.title).value)
            val end = end_section()

            val middleRows = section.fields
              .filterNot(_.hideOnSummary)
              .map(v =>
                new govukSummaryList()(SummaryList(getSummaryListRows(
                  v,
                  formTemplate._id,
                  data,
                  maybeAccessCode,
                  section.shortName.getOrElse(section.title).value,
                  SectionNumber(index),
                  sectionTitle4Ga,
                  obligations,
                  fields,
                  validatedType,
                  envelope
                ))))

            if (middleRows.isEmpty) {
              Nil
            } else {
              begin +: middleRows
            }
        }
    }

    val sections = RepeatingComponentService.getAllSections(formTemplate, data)

    val fields = sections.flatMap(RepeatingComponentService.atomicFields(_, data.data))

    renderHtmls(sections, fields)
  }

  def summaryForNotificationPdf(
    validatedType: ValidatedType[ValidationResult],
    data: FormDataRecalculated,
    maybeAccessCode: Option[AccessCode],
    formTemplate: FormTemplate,
    envelope: Envelope,
    obligations: Obligations,
    reviewerComments: Option[String] = None,
    pdfFieldIds: List[FormComponentId]
  )(
    implicit
    messages: Messages,
    l: LangADT,
    lise: SmartStringEvaluator): List[Html] = {

    def renderHtmls(fields: List[FormComponent])(implicit l: LangADT): List[Html] =
      fields
        .map(
          formComponent =>
            valueToHtml(
              formComponent,
              formTemplate._id,
              data,
              maybeAccessCode,
              "",
              SectionNumber(0),
              SectionTitle4Ga(""),
              obligations,
              fields,
              validatedType,
              envelope))

    val allFormComponents =
      formTemplate.expandFormTemplateFull.formComponentsLookupFull

    val nonEmptyFormComponentIds =
      data.data.data.toList
        .filter {
          _._2.toSeq.map(_.nonEmpty).head
        }
        .map(_._1)

    val nonEmptyFormComponents: List[(FormComponentId, FormComponent)] = nonEmptyFormComponentIds.flatMap { fcId =>
      allFormComponents.find(_._1 == fcId)
    }

    val filteredFormComponents: List[FormComponent] = pdfFieldIds
      .flatMap { fcId =>
        nonEmptyFormComponents.find(_._1.value.startsWith(fcId.value))
      }
      .map(_._2)

    renderHtmls(filteredFormComponents)
  }

  private def validate(
    formComponent: FormComponent,
    validatedType: ValidatedType[ValidationResult],
    data: FormDataRecalculated,
    fields: List[FormComponent],
    envelope: Envelope): Option[FormFieldValidationResult] = {
    val gformErrors = validatedType match {
      case Invalid(errors) => errors
      case Valid(_)        => Map.empty[FormComponentId, Set[String]]
    }
    Fields.getValidationResult(data, fields, envelope, gformErrors)(formComponent)
  }

  private def getSummaryListRows(
    fieldValue: FormComponent,
    formTemplateId: FormTemplateId,
    data: FormDataRecalculated,
    maybeAccessCode: Option[AccessCode],
    title: String,
    sectionNumber: SectionNumber,
    sectionTitle4Ga: SectionTitle4Ga,
    obligations: Obligations,
    fields: List[FormComponent],
    validatedType: ValidatedType[ValidationResult],
    envelope: Envelope)(
    implicit
    messages: Messages,
    l: LangADT,
    lise: SmartStringEvaluator): List[SummaryListRow] = {

    def groupHelper(fieldValue: FormComponent, presentationHint: List[PresentationHint])(
      implicit l: LangADT): List[SummaryListRow] = {
      val isLabel = fieldValue.shortName.map(ls => ls.value).getOrElse(fieldValue.label.value).nonEmpty

      fieldValue.`type` match {
        case groupField: Group if presentationHint.contains(SummariseGroupAsGrid) && groupField.repeatsMax.isDefined =>
          val summaryListRows = {
            val groups =
              getAllFieldsInGroup(fieldValue, groupField, data).filter(_.hasData(data))

            for {
              group <- groups

              validationResult = validate(fieldValue, validatedType, data, fields, envelope)

              hasErrors = validationResult.exists(_.isNotOk)

              errors = {
                validationResult.map(_.fieldErrors.toList).getOrElse(Set().toList).map { e =>
                  errorInline(s"${fieldValue.id.value}-error-message", e, Seq("error-message"))
                }
              }

              label = fieldValue.shortName.map(ls => ls.value).getOrElse(fieldValue.label.value)

              visuallyHiddenText = Some(fieldValue.shortName.map(ls => ls.value).getOrElse(fieldValue.label.value))

              keyClasses = if (hasErrors)
                "summary--error"
              else
                ""

              value = if (hasErrors)
                errors.mkString(" ")
              else
                group.componentList
                  .map(v => validate(v, validatedType, data, fields, envelope))
                  .map(f => s"${TextFormatter.formatText(f)}<br>")
                  .mkString

            } yield
              summaryListRow(
                label,
                value,
                visuallyHiddenText,
                keyClasses,
                "",
                "",
                (
                  uk.gov.hmrc.gform.gform.routes.FormController
                    .form(formTemplateId, maybeAccessCode, sectionNumber, sectionTitle4Ga, SeYes),
                  if (fieldValue.editable) messages("summary.change") else messages("summary.view"))
              )
          }

          summaryListRows

        case groupField: Group if presentationHint.contains(SummariseGroupAsGrid) =>
          val fcs: List[FormComponent] =
            getAllFieldsInGroup(fieldValue, groupField, data).filter(_.hasData(data)).flatMap(_.componentList)

          val validationResult = validate(fieldValue, validatedType, data, fields, envelope)

          val hasErrors = validationResult.exists(_.isNotOk)

          val errors = {
            validationResult.map(_.fieldErrors.toList).getOrElse(Set().toList).map { e =>
              errorInline(s"${fieldValue.id.value}-error-message", e, Seq("error-message"))
            }
          }

          val label = fieldValue.shortName.map(ls => ls.value).getOrElse(fieldValue.label.value)

          val visuallyHiddenText = Some(fieldValue.shortName.map(ls => ls.value).getOrElse(fieldValue.label.value))

          val keyClasses =
            if (hasErrors)
              "summary--error"
            else
              ""

          val value =
            if (hasErrors)
              errors.mkString(" ")
            else
              fcs
                .map(v => validate(v, validatedType, data, fields, envelope))
                .filterNot(_.isEmpty)
                .map(f => s"${TextFormatter.formatText(f)}<br>")
                .mkString

          if (value.nonEmpty) {
            List(
              summaryListRow(
                label,
                value,
                visuallyHiddenText,
                keyClasses,
                "",
                "",
                (
                  uk.gov.hmrc.gform.gform.routes.FormController
                    .form(formTemplateId, maybeAccessCode, sectionNumber, sectionTitle4Ga, SeYes),
                  if (fieldValue.editable) messages("summary.change") else messages("summary.view"))
              ))

          } else List(SummaryListRow())

        case groupField @ Group(_, _, _, _, _) =>
          val fvs: List[GroupList] =
            getAllFieldsInGroup(fieldValue, groupField, data)

          val summaryListRows = fvs.flatMap(_.componentList.flatMap { fv =>
            getSummaryListRows(
              fv,
              formTemplateId,
              data,
              maybeAccessCode,
              "",
              sectionNumber,
              sectionTitle4Ga,
              obligations,
              fields,
              validatedType,
              envelope
            )
          })

          val groupSummaryListRow = if (isLabel) {
            val label = fieldValue.shortName.map(ls => ls.value).getOrElse(fieldValue.label.value)

            val keyClasses = "summary-group-label"

            List(
              summaryListRow(label, "", None, keyClasses, "", "")
            )
          } else Nil

          groupSummaryListRow ++ summaryListRows

        case _ =>
          getSummaryListRows(
            fieldValue,
            formTemplateId,
            data,
            maybeAccessCode,
            "",
            sectionNumber,
            sectionTitle4Ga,
            obligations,
            fields,
            validatedType,
            envelope
          )
      }
    }

    fieldValue.`type` match {
      case Text(_, _, _, _) =>
        val validationResult = validate(fieldValue, validatedType, data, fields, envelope)
        val hasErrors = validationResult.exists(_.isNotOk)
        val errors = {
          validationResult.map(_.fieldErrors.toList).getOrElse(Set().toList).map { e =>
            errorInline(s"${fieldValue.id.value}-error-message", e, Seq("error-message"))
          }
        }
        val label = fieldValue.shortName.map(ls => ls.value).getOrElse(fieldValue.label.value)
        val value = if (hasErrors) errors.mkString(" ") else formatText(validationResult)
        val visuallyHiddenText = Some(fieldValue.shortName.map(ls => ls.value).getOrElse(fieldValue.label.value))
        val keyClasses =
          if (hasErrors)
            "summary--error"
          else
            ""

        List(
          summaryListRow(
            label,
            value,
            visuallyHiddenText,
            keyClasses,
            "",
            "",
            (
              uk.gov.hmrc.gform.gform.routes.FormController
                .form(formTemplateId, maybeAccessCode, sectionNumber, sectionTitle4Ga, SeYes),
              if (fieldValue.editable) messages("summary.change") else messages("summary.view"))
          ))

      case TextArea(_, _, _) =>
        val validationResult = validate(fieldValue, validatedType, data, fields, envelope)

        val hasErrors = validationResult.exists(_.isNotOk)

        val errors = validationResult.map(_.fieldErrors.toList).getOrElse(Set().toList).map { e =>
          errorInline("summary", e, Seq())
        }

        val label = fieldValue.shortName.map(ls => ls.value).getOrElse(fieldValue.label.value)

        val currentValueLines = formatText(validationResult).split("\\R")

        val currentValue = if (currentValueLines.nonEmpty) {
          currentValueLines.init.map { line =>
            s"$line<br>"
          }
          currentValueLines.last
        } else ""

        val value = if (hasErrors) errors.mkString(" ") else currentValue
        val visuallyHiddenText = Some(fieldValue.shortName.map(ls => ls.value).getOrElse(fieldValue.label.value))

        val keyClasses =
          if (hasErrors)
            "summary--error"
          else
            ""

        List(
          summaryListRow(
            label,
            value,
            visuallyHiddenText,
            keyClasses,
            "",
            "",
            (
              uk.gov.hmrc.gform.gform.routes.FormController
                .form(formTemplateId, maybeAccessCode, sectionNumber, sectionTitle4Ga, SeYes),
              if (fieldValue.editable) messages("summary.change") else messages("summary.view"))
          ))

      case UkSortCode(_) =>
        val validationResult = validate(fieldValue, validatedType, data, fields, envelope)

        val hasErrors = validationResult.exists(_.isNotOk)

        val errors = validationResult.map(_.fieldErrors.toList).getOrElse(Set().toList).map { e =>
          errorInline("summary", e, Seq())
        }

        val label = fieldValue.shortName.map(ls => ls.value).getOrElse(fieldValue.label.value)

        val currentValue = UkSortCode
          .fields(fieldValue.id)
          .toList
          .map { fieldId =>
            validationResult.map(_.getCurrentValue(fieldId.toString)).getOrElse("")
          }
          .mkString("-")

        val value = if (hasErrors) errors.mkString(" ") else currentValue

        val keyClasses =
          if (hasErrors)
            "summary--error"
          else
            ""

        List(
          summaryListRow(
            label,
            value,
            None,
            keyClasses,
            "",
            "",
            (
              uk.gov.hmrc.gform.gform.routes.FormController
                .form(formTemplateId, maybeAccessCode, sectionNumber, sectionTitle4Ga, SeYes),
              if (fieldValue.editable) messages("summary.change") else messages("summary.view"))
          ))

      case Date(_, _, _) =>
        val validationResult = validate(fieldValue, validatedType, data, fields, envelope)

        val hasErrors = validationResult.exists(_.isNotOk)

        val errors = validationResult.map(_.fieldErrors.toList).getOrElse(Set().toList).map { e =>
          errorInline("summary", e, Seq())
        }

        def safeId(id: String) = fieldValue.id.withSuffix(id).toString

        def monthKey = getMonthValue(validationResult.map(_.getCurrentValue(safeId("month")))).getOrElse("")

        val label = fieldValue.shortName.map(ls => ls.value).getOrElse(fieldValue.label.value)

        val value =
          if (hasErrors)
            errors.head.toString
          else {
            val day = renderMonth(validationResult.map(_.getCurrentValue(safeId("day")))).getOrElse("")
            val month = messages(s"date.$monthKey")
            val year = validationResult.map(_.getCurrentValue(safeId("year"))).getOrElse("")

            s"$day $month $year"
          }

        val keyClasses =
          if (hasErrors)
            "summary--error"
          else
            ""

        List(
          summaryListRow(
            label,
            value,
            None,
            keyClasses,
            "",
            "",
            (
              uk.gov.hmrc.gform.gform.routes.FormController
                .form(formTemplateId, maybeAccessCode, sectionNumber, sectionTitle4Ga, SeYes),
              if (fieldValue.editable) messages("summary.change") else messages("summary.view"))
          ))

      case Address(_) =>
        val validationResult = validate(fieldValue, validatedType, data, fields, envelope)

        val hasErrors = validationResult.exists(_.isNotOk)

        val errors = validationResult.map(_.fieldErrors.toList).getOrElse(Set().toList).map { e =>
          errorInline("summary", e, Seq())
        }

        def safeId(id: String) = fieldValue.id.withSuffix(id).value

        def showError(e: String) = errorInline(e, e, Seq("error-message"))

        val label = fieldValue.shortName.map(ls => ls.value.capitalize).getOrElse(fieldValue.label.value)

        val keyClasses =
          if (hasErrors)
            "summary--error"
          else
            ""

        val value = if (hasErrors) {
          errors.mkString(" ")
        } else {
          List(
            "street1",
            "street2",
            "street3",
            "street4",
            "postcode",
            "country"
          ).map { suffix =>
            if (validationResult.map(_.getCurrentValue(safeId(suffix)).isEmpty).contains(false)) {
              validationResult.map(_.getCurrentValue(safeId(suffix))).getOrElse("") + "<br>"
            } else ""
          }.mkString
        }

        List(
          summaryListRow(
            label,
            value,
            None,
            keyClasses,
            "",
            "",
            (
              uk.gov.hmrc.gform.gform.routes.FormController
                .form(formTemplateId, maybeAccessCode, sectionNumber, sectionTitle4Ga, SeYes),
              if (fieldValue.editable) messages("summary.change") else messages("summary.view"))
          ))

      case InformationMessage(_, _) =>
        List(SummaryListRow())

      case f @ FileUpload() =>
        val validationResult = validate(fieldValue, validatedType, data, fields, envelope)

        val hasErrors = validationResult.exists(_.isNotOk)

        val errors = validationResult.map(_.fieldErrors.toList).getOrElse(Set().toList).map { e =>
          errorInline("summary", e, Seq("error-message"))
        }

        val label = fieldValue.shortName.map(ls => ls.value).getOrElse(fieldValue.label.value)

        val value = if (hasErrors) errors.mkString(" ") else validationResult.flatMap(_.getCurrentValue).getOrElse("")

        val keyClasses =
          if (hasErrors)
            "summary--error"
          else
            ""

        List(
          summaryListRow(
            label,
            value,
            None,
            keyClasses,
            "",
            "",
            (
              uk.gov.hmrc.gform.gform.routes.FormController
                .form(formTemplateId, maybeAccessCode, sectionNumber, sectionTitle4Ga, SeYes),
              if (fieldValue.editable) messages("summary.change") else messages("summary.view"))
          ))

      case h @ HmrcTaxPeriod(_, _, _) =>
        val validationResult = validate(fieldValue, validatedType, data, fields, envelope)

        val hasErrors = validationResult.exists(_.isNotOk)

        val errors = validationResult.map(_.fieldErrors.toList).getOrElse(Set().toList).map { e =>
          errorInline("summary", e, Seq())
        }

        val label = fieldValue.shortName.map(ls => ls.value).getOrElse(fieldValue.label.value)

        val periodId =
          TaxPeriodHelper.formatTaxPeriodOutput(validate(fieldValue, validatedType, data, fields, envelope))
        val maybeObligation = obligations.findByPeriodKey(h, periodId)

        val value =
          if (hasErrors)
            errors.mkString(" ")
          else
            maybeObligation.fold("Value Lost!") { od =>
              messages("generic.From") + " " + formatDate(od.inboundCorrespondenceFromDate) + " " +
                messages("generic.to") + " " + formatDate(od.inboundCorrespondenceToDate)
            }

        val keyClasses =
          if (hasErrors)
            "summary--error"
          else
            ""

        List(
          summaryListRow(
            label,
            value,
            None,
            keyClasses,
            "",
            "",
            (
              uk.gov.hmrc.gform.gform.routes.FormController
                .form(formTemplateId, maybeAccessCode, sectionNumber, sectionTitle4Ga, SeYes),
              if (fieldValue.editable) messages("summary.change") else messages("summary.view"))
          ))

      case Choice(_, options, _, _, _) =>
        val validationResult = validate(fieldValue, validatedType, data, fields, envelope)

        val hasErrors = validationResult.exists(_.isNotOk)

        val errors = validationResult.map(_.fieldErrors.toList).getOrElse(Set().toList).map { e =>
          errorInline("summary", e, Seq())
        }

        val label = fieldValue.shortName.map(ls => ls.value).getOrElse(fieldValue.label.value)

        val value =
          if (hasErrors)
            errors.mkString(" ")
          else
            options.toList.zipWithIndex
              .map {
                case (option, index) =>
                  validate(fieldValue, validatedType, data, fields, envelope)
                    .flatMap(_.getOptionalCurrentValue(fieldValue.id.value + index.toString))
                    .map(_ => option.value)
              }
              .collect { case Some(selection) => selection }
              .map(s => s"<p>$s</p>")
              .mkString

        val keyClasses =
          if (hasErrors)
            "summary--error"
          else
            ""

        List(
          summaryListRow(
            label,
            value,
            None,
            keyClasses,
            "",
            "",
            (
              uk.gov.hmrc.gform.gform.routes.FormController
                .form(formTemplateId, maybeAccessCode, sectionNumber, sectionTitle4Ga, SeYes),
              if (fieldValue.editable) messages("summary.change") else messages("summary.view"))
          ))

      case rc: RevealingChoice =>
        val selections: NonEmptyList[Option[List[SummaryListRow]]] = rc.options.zipWithIndex
          .map {
            case (element, index) =>
              val validationResult = validate(fieldValue, validatedType, data, fields, envelope)

              val hasErrors = validationResult.exists(_.isNotOk)

              val errors = validationResult.map(_.fieldErrors.toList).getOrElse(Set().toList).map { e =>
                errorInline("summary", e, Seq())
              }

              val label = fieldValue.shortName.map(ls => ls.value).getOrElse(fieldValue.label.value)

              val value =
                if (hasErrors)
                  errors.mkString(" ")
                else
                  element.choice.value

              val keyClasses =
                if (hasErrors)
                  "summary--error"
                else
                  ""

              validationResult
                .flatMap(_.getOptionalCurrentValue(fieldValue.id.value + index.toString))
                .map { _ =>
                  val revealingFields = element.revealingFields.filterNot(_.hideOnSummary).flatMap {
                    getSummaryListRows(
                      _,
                      formTemplateId,
                      data,
                      maybeAccessCode,
                      "",
                      sectionNumber,
                      sectionTitle4Ga,
                      obligations,
                      fields,
                      validatedType,
                      envelope
                    )
                  }

                  summaryListRow(
                    label,
                    value,
                    None,
                    keyClasses,
                    "",
                    "",
                    (
                      uk.gov.hmrc.gform.gform.routes.FormController
                        .form(formTemplateId, maybeAccessCode, sectionNumber, sectionTitle4Ga, SeYes),
                      if (fieldValue.editable) messages("summary.change") else messages("summary.view"))
                  ) +: revealingFields
                }
          }

        selections.collect { case Some(v) => v }.flatten

      case Group(_, _, _, _, _) => groupHelper(fieldValue, fieldValue.presentationHint.getOrElse(Nil))

    }
  }

  private def valueToHtml(
    fieldValue: FormComponent,
    formTemplateId: FormTemplateId,
    data: FormDataRecalculated,
    maybeAccessCode: Option[AccessCode],
    title: String,
    sectionNumber: SectionNumber,
    sectionTitle4Ga: SectionTitle4Ga,
    obligations: Obligations,
    fields: List[FormComponent],
    validatedType: ValidatedType[ValidationResult],
    envelope: Envelope)(
    implicit
    messages: Messages,
    l: LangADT,
    lise: SmartStringEvaluator): Html = {

    val changeButton = change_button(
      formTemplateId,
      maybeAccessCode,
      title,
      sectionNumber,
      sectionTitle4Ga,
      fieldValue
    )

    def groupToHtml(fieldValue: FormComponent, presentationHint: List[PresentationHint])(implicit l: LangADT): Html = {
      val isLabel = fieldValue.shortName.map(ls => ls.value).getOrElse(fieldValue.label.value).nonEmpty

      fieldValue.`type` match {
        case groupField: Group if presentationHint.contains(SummariseGroupAsGrid) && groupField.repeatsMax.isDefined =>
          val htmlList: List[Html] = {

            val groups: List[GroupList] =
              getAllFieldsInGroup(fieldValue, groupField, data).filter(_.hasData(data))

            for {
              group <- groups
              value = group.componentList.map(v => validate(v, validatedType, data, fields, envelope))
            } yield group_grid(fieldValue, value, false, changeButton)

          }

          flatten(htmlList)
        case groupField: Group if presentationHint.contains(SummariseGroupAsGrid) =>
          val fcs: List[FormComponent] =
            getAllFieldsInGroup(fieldValue, groupField, data).filter(_.hasData(data)).flatMap(_.componentList)

          val value = fcs.map(v => validate(v, validatedType, data, fields, envelope)).filterNot(_.isEmpty)

          if (value.nonEmpty) {
            group_grid(fieldValue, value, isLabel, changeButton)
          } else Html("")

        case groupField @ Group(_, _, _, _, _) =>
          val fvs: List[GroupList] =
            getAllFieldsInGroup(fieldValue, groupField, data)

          val htmlList = fvs.flatMap(_.componentList.map { fv =>
            valueToHtml(
              fv,
              formTemplateId,
              data,
              maybeAccessCode,
              "",
              sectionNumber,
              sectionTitle4Ga,
              obligations,
              fields,
              validatedType,
              envelope
            )
          })
          group(fieldValue, htmlList, isLabel)

        case _ =>
          valueToHtml(
            fieldValue,
            formTemplateId,
            data,
            maybeAccessCode,
            "",
            sectionNumber,
            sectionTitle4Ga,
            obligations,
            fields,
            validatedType,
            envelope
          )
      }
    }

    fieldValue.`type` match {
      case UkSortCode(_) =>
        sort_code(fieldValue, validate(fieldValue, validatedType, data, fields, envelope), changeButton)
      case Date(_, _, _) => date(fieldValue, validate(fieldValue, validatedType, data, fields, envelope), changeButton)
      case Address(_)    => address(fieldValue, validate(fieldValue, validatedType, data, fields, envelope), changeButton)
      case Text(_, _, _, _) =>
        text(fieldValue, validate(fieldValue, validatedType, data, fields, envelope), changeButton)
      case TextArea(_, _, _) =>
        textarea(fieldValue, validate(fieldValue, validatedType, data, fields, envelope), changeButton)

      case Choice(_, options, _, _, _) =>
        val selections = options.toList.zipWithIndex
          .map {
            case (option, index) =>
              validate(fieldValue, validatedType, data, fields, envelope)
                .flatMap(_.getOptionalCurrentValue(fieldValue.id.value + index.toString))
                .map(_ => option.value)
          }
          .collect { case Some(selection) => selection }

        choice(fieldValue, selections, changeButton)

      case rc: RevealingChoice =>
        val selections = rc.options.zipWithIndex
          .map {
            case (element, index) =>
              validate(fieldValue, validatedType, data, fields, envelope)
                .flatMap(_.getOptionalCurrentValue(fieldValue.id.value + index.toString))
                .map { _ =>
                  val selections: List[Html] = element.revealingFields.filterNot(_.hideOnSummary).map {
                    valueToHtml(
                      _,
                      formTemplateId,
                      data,
                      maybeAccessCode,
                      "",
                      sectionNumber,
                      sectionTitle4Ga,
                      obligations,
                      fields,
                      validatedType,
                      envelope
                    )
                  }

                  revealingChoice(element.choice, fieldValue, selections, changeButton)
                }
          }
          .collect { case Some(html) => html }

        flatten(selections)

      case f @ FileUpload() =>
        file_upload(fieldValue, validate(fieldValue, validatedType, data, fields, envelope), changeButton)
      case InformationMessage(_, _) => Html("")
      case Group(_, _, _, _, _)     => groupToHtml(fieldValue, fieldValue.presentationHint.getOrElse(Nil))

      case h @ HmrcTaxPeriod(_, _, _) =>
        val periodId =
          TaxPeriodHelper.formatTaxPeriodOutput(validate(fieldValue, validatedType, data, fields, envelope))
        val maybeObligation = obligations.findByPeriodKey(h, periodId)
        hmrc_tax_period(
          fieldValue,
          validate(fieldValue, validatedType, data, fields, envelope),
          changeButton,
          maybeObligation)
    }
  }
}
